# Migración a AWS Lambda (opción C2)

Iniciada el 2026-08-13. **Desde el 2026-08-14, `/api/*` lo sirve Lambda** a través de una
API Gateway REST API. ECS sigue levantado y sin tráfico como vía de vuelta; el procedimiento
para volver está en `docs/runbook-vuelta-atras-lambda.md`. Ver `docs/aws-architecture.md`
para el resto de la arquitectura.

Objetivo: eliminar el ALB (17,58 USD/mes) y sus IPv4 (12,58), que tras los recortes del
2026-08-13 suponían el 65% de la factura y no responden a la demanda —el `LCUUsage` del
balanceador fue de **0,0018 USD** en julio—.

## Bloqueantes verificados (2026-08-13)

Comprobados con una función de prueba desechable en `mx-central-1`, ya eliminada:

| Incógnita | Resultado |
|---|---|
| ¿Existe runtime gestionado para Java 25? | ✅ **Sí.** No hace falta bajar a Java 21 |
| ¿SnapStart disponible en la región? | ✅ **Sí**, `ApplyOn: PublishedVersions` aceptado |
| ¿Cabe el paquete? | ✅ 58 MB, bajo el límite de 250 MB. Al superar 50 MB hay que subirlo vía S3 |
| ¿El adaptador soporta Spring Boot 4.1? | ✅ **Sí**, `aws-serverless-java-container-springboot4:3.0.0` |

## Decisiones

### Adaptador: serverless-java-container, no Lambda Web Adapter

Se descartó inicialmente LWA por creerlo incompatible con SnapStart; **esa objeción era
falsa**, LWA también funciona sobre runtimes gestionados. El motivo real de la elección es
que el adaptador de AWS no levanta un servidor HTTP ni añade un salto de proxy.

Se usa `getAwsProxyHandler`, que es el del ejemplo oficial, porque la integración proxy de
una API Gateway REST API emite payload **v1**. Se llegó ahí por descarte, no por elección:
ver el bloqueante más abajo.

### Empaquetado: perfil `lambda` con shade, no el fat jar de Boot

    mvn package            -> fat jar de 61 MB, el que despliega ECS
    mvn package -Plambda   -> target/bible-references-lambda.jar, 58 MB

Dos artefactos del mismo código a propósito: mientras dure la migración ECS debe poder
desplegarse sin cambios. Es la vía de vuelta.

Hace falta `shade` porque el fat jar de Boot coloca las clases bajo `BOOT-INF/`, disposición
que el runtime Java de Lambda no sabe cargar.

> **Trampa que costó encontrar.** Declarar `<transformers>` propios en el perfil hace fallar
> el build con `Cannot find 'resource' in class ServicesResourceTransformer`. La causa no es
> el orden ni la versión del plugin: `spring-boot-starter-parent` ya declara **siete**
> transformers para la ejecución `shade`, en la misma fase y con el mismo id, y Maven fusiona
> ambas listas **posición a posición**, mezclando transformers de tipos distintos.
>
> La solución es no declarar ninguno: la lista del padre es más completa y además usa
> `PropertiesMergingResourceTransformer` para `spring.factories`, que fusiona claves en vez
> de concatenar ficheros. Si algún día hiciera falta añadir uno, hay que replicar la lista
> entera del padre y marcarla `combine.self="override"`.

Se excluyen del paquete el Tomcat embebido —el adaptador emula el contenedor de servlets— y
**DevTools**, que el repackage de Boot excluye por su cuenta pero shade no: se colaron 154
clases con watchers de ficheros en el primer intento.

### SnapStart: el pool de conexiones lo gestiona Spring, no código propio

El problema es real: SnapStart captura la memoria **después** de la inicialización, cuando
HikariCP ya tiene sockets abiertos contra PostgreSQL. Sin tratarlo, la función arrancaría
bien y fallaría en la primera consulta tras cada restauración.

**La solución es una sola dependencia: `org.crac:crac`.** Con ella en el classpath, Spring
Boot activa `HikariCheckpointRestoreLifecycle` (en `spring-boot-jdbc`), que suspende y vacía
el pool antes del snapshot y lo reanuda al restaurar. Requiere
`spring.datasource.hikari.allow-pool-suspension: true`, ya presente en `application.yaml`.

> **Error cometido y corregido el 2026-08-13.** Se escribió un `org.crac.Resource` propio
> —`DataSourceCheckpointResource`— sin saber que Spring ya hacía ese trabajo. No solo era
> redundante: Lambda ejecuta los ganchos en orden inverso al de registro, así que el propio
> corría **después** del de Spring, pedía una conexión a un pool ya suspendido y se quedaba
> bloqueado hasta agotar los **130 segundos** de la fase de init. La función publicaba
> versiones en estado `Failed` con el mensaje genérico "An error occurred during function
> initialization", que no apunta a nada.
>
> El síntoma que lo delató estaba en los logs, justo antes del timeout:
>
> ```
> o.s.b.j.HikariCheckpointRestoreLifecycle : Suspending Hikari pool
> o.s.b.j.HikariCheckpointRestoreLifecycle : Evicting Hikari connections
> INIT_REPORT Init Duration: 130000.51 ms  Phase: init  Status: timeout
> ```
>
> Lección: antes de escribir un gancho de CRaC, comprobar qué hace ya el framework.

### Credenciales: variables de entorno, no Secrets Manager

Una Lambda en VPC **no tiene salida a internet**. Leer Secrets Manager desde ahí exigiría un
VPC endpoint de interfaz (~7,30 USD/mes) o un NAT gateway (~32 USD/mes) — sobre una factura
objetivo de ~11 USD/mes, cualquiera de los dos se come el ahorro.

La aplicación **no necesita ningún cambio**: Spring ya lee `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` del entorno, que son los mismos
nombres que hoy inyecta ECS desde el secreto.

Al crear la función, sin exponer los valores en pantalla:

```bash
SECRET=$(aws secretsmanager get-secret-value --secret-id bible-references/db \
  --query SecretString --output text)
# extraer url/username/password de $SECRET y pasarlos a --environment
```

> **Lo que se pierde:** rotación automática y trazabilidad de acceso. Cualquiera con
> `lambda:GetFunctionConfiguration` puede leer la contraseña. Se cifran en reposo con la
> clave KMS gestionada por AWS, que es gratuita.
>
> **Alternativa mejor, pendiente de evaluar:** autenticación IAM de RDS. Elimina la
> contraseña por completo —la aplicación genera un token con su rol de ejecución— y **no
> requiere salida a internet**, porque el token se calcula localmente. Exige activar IAM auth
> en la instancia, crear un usuario mapeado y un proveedor de credenciales propio para Hikari
> que renueve el token cada 15 minutos. Es más trabajo, pero es la opción correcta a futuro.

## BLOQUEANTE — En `mx-central-1` solo hay REST API

La región no tiene ninguna de las dos formas modernas de exponer una Lambda por HTTP. Se
descubrió en dos tandas, y la segunda desmintió lo que se había dado por bueno en la primera.

**Function URLs** (2026-08-13):

```
aws lambda create-function-url-config --function-name bible-references --auth-type AWS_IAM
AccessDeniedException: Unable to determine service/operation name to be authorized
```

**API Gateway HTTP API** (2026-08-14):

```
aws apigatewayv2 create-api --name bible-references-api --protocol-type HTTP ...
BadRequestException: HTTP APIs are not available in this AWS region
```

> **El error de método que costó una sesión.** El 2026-08-13 se dio la región por buena
> para HTTP API porque `apigatewayv2 get-apis` respondía sin error. **Listar no prueba que
> se pueda crear:** el endpoint de la API existe en la región aunque el recurso no se pueda
> instanciar. La comprobación válida es intentar crear el recurso de verdad —y borrarlo si
> no hace falta—, que es como se verificó que la REST API sí está disponible.
>
> No es un problema de permisos: el usuario tiene `AdministratorAccess` y el resto de
> operaciones funcionan en la misma sesión.

**La función se expone con API Gateway REST API regional.** Verificado creando una API de
sonda, que se borró acto seguido.

| | Function URL (previsto) | REST API (real) |
|---|---|---|
| Coste | 0 | 3,50 USD por millón → **~0,001 USD/mes** con este tráfico |
| Formato de evento | payload v2 | **payload v1** — obliga a `getAwsProxyHandler` |
| Protección del origen | **OAC**: CloudFront firma con SigV4 | Sigue haciendo falta el header secreto `X-Origin-Verify` |
| Piezas a mantener | una menos | una más |

El coste sigue siendo despreciable y la economía de la migración no cambia. Lo que sí se
pierde es la mejora de seguridad prevista: **el secreto compartido se queda en la
arquitectura**, de modo que rotarlo (tarea 15) pasa de conveniente a necesario.

> Descartada la alternativa de registrar la Lambda como target del ALB: funcionaría, pero
> conserva el balanceador, que es justamente el gasto que esta migración quiere eliminar.

### Quién comprueba ahora el header secreto

En ECS lo hacía **la regla del listener del ALB**. API Gateway no tiene enrutado por
cabecera, así que al salir el ALB del camino esa comprobación se quedaba sin dueño: la URL
de ejecución habría quedado abierta a cualquiera que la conociera, y con ella se esquivan
WAF, rate limiting y caché.

La hace ahora la propia aplicación, en `OriginVerifyFilter`:

- Se activa **solo si `ORIGIN_VERIFY_SECRET` está definida**, que ocurre únicamente en la
  función Lambda. En ECS y en local el filtro deja pasar todo y nada cambia de
  comportamiento.
- Corre antes que `OriginValidationFilter` (`@Order(0)` frente a `@Order(1)`). Son cosas
  distintas: aquél valida **qué frontend** llama (`X-Client-Origin`, falsificable); éste,
  que la petición **atravesó nuestro CDN**.
- Compara en tiempo constante y responde `403 Forbidden` a secas, sin nombrar la cabecera.
- `/actuator/health` queda exento: el health check del target group lo emite el ALB, que no
  envía cabeceras propias. Sin esa excepción, activar el secreto en ECS mataría las tasks.

> **Alcance de las pruebas.** `OriginVerifyFilterTest` cubre la lógica con las dos mitades
> que importan —que rechace con secreto y que **no estorbe sin él**, porque eso último es lo
> que rompería ECS—. Que el filtro esté realmente en la cadena dentro de Lambda no lo prueba
> ninguna prueba local: se verificó contra la API Gateway desplegada.

### El `Host` que habría roto todo

API Gateway responde **403 a cualquier petición cuyo `Host` no sea el suyo**. El
comportamiento `/api/*` usa la política de origen `Managed-AllViewerExceptHostHeader`
(`b689b0a8-...`), que reenvía todas las cabeceras del visor **menos** `Host`, así que
funciona. Con `Managed-AllViewer` —que es la que sugiere el nombre y estuvo a un paso de
usarse— CloudFront habría reenviado `escrituraclave.com` y **la API habría devuelto 403 a
todo el tráfico**, con un error que no menciona el `Host` por ningún lado.

## Caché en el borde para capítulos populares

La aplicación decide qué se cachea; CloudFront solo obedece. **Añadir un capítulo es tocar
una única lista en el código**, sin reconfigurar el CDN ni crear un comportamiento por ruta.

- `VerseCacheCondition` contiene los capítulos populares. Ya existía para la caché en
  memoria, y ahora gobierna también la del borde: si un capítulo merece cachearse en
  proceso, merece cachearse en el CDN.
- `BibleController` emite `Cache-Control: public, max-age=86400` para esos capítulos y
  `no-store` para el resto. **El comportamiento por defecto es no cachear**, de modo que
  ampliar la caché sea siempre una decisión explícita.
- Política de caché de CloudFront `bible-api-honor-origin-cache-control`
  (`c1f5aa73-ce63-401c-bdc5-ea6b524938b3`), creada el 2026-08-13 y **aún sin asociar**:
  `DefaultTTL=0` (sin cabecera no se cachea nada), `MaxTTL=1 año`.

`X-Client-Origin` va en la clave de caché a propósito: la respuesta **depende** de esa
cabecera (403 sin ella, 200 con ella). Si no estuviera en la clave, la petición de un bot y
la de un usuario compartirían entrada y el rechazo podría servirse a quien no toca.

Para asociarla, una vez desplegada la aplicación que emite las cabeceras:

```bash
aws cloudfront get-distribution-config --id E1MTT1XP2UUMYU > dist.json
# CacheBehaviors.Items[0].CachePolicyId -> c1f5aa73-ce63-401c-bdc5-ea6b524938b3
aws cloudfront update-distribution --id E1MTT1XP2UUMYU \
  --distribution-config file://config.json --if-match <ETag>
```

Reversión: volver a `4135ea2d-6df8-44a3-9df3-4b5a84be39ad` (`Managed-CachingDisabled`).

> **Por qué esto y no subir el TTL de todo.** CloudFront desaloja los objetos poco
> solicitados **aunque no haya expirado su TTL**. Con ~9 peticiones reales al día repartidas
> entre 1.189 capítulos, el ratio de acierto sería malo. Acotarlo a un puñado de capítulos
> populares concentra las peticiones en pocas entradas, que es la única forma de que se
> mantengan calientes. No resuelve el arranque en frío del resto: eso solo lo arregla C3.

## PENDIENTE — La API devuelve 200 con HTML cuando falla

Independiente de la migración y conviene arreglarlo antes de activar la caché.

La distribución tiene *custom error responses* que reescriben **403 y 404 a 200 con
`/index.html`**, y son de ámbito global, así que aplican también a `/api/*`:

| Error del origen | Lo que recibe el cliente |
|---|---|
| 403 (filtro de origen) | 200 + `index.html` de la SPA |
| 404 (capítulo inexistente) | 200 + `index.html` de la SPA |

Dos consecuencias. El frontend **no puede distinguir un error de una respuesta válida**, y
cualquier monitor externo verá 200 siempre. Y de cara a la caché, con
`ErrorCachingMinTTL: 10` CloudFront puede guardar ese HTML asociado a una ruta de API.

> Este comportamiento invalidó buena parte de la verificación hecha durante la sesión del
> 2026-08-13: `curl` sin `X-Client-Origin` devolvía 200 y se interpretó como que la API
> funcionaba, cuando era el `index.html`. **Toda comprobación manual de la API debe enviar
> la cabecera:**
>
> ```bash
> curl -H "X-Client-Origin: https://escrituraclave.com" \
>   https://escrituraclave.com/api/bible/chapter/1/1
> ```

La corrección pasa por acotar esas respuestas de error para que no cubran `/api/*`. No es
trivial —son globales en CloudFront— y suele resolverse con una CloudFront Function o
moviendo la SPA a un comportamiento propio.

## La función, medida (2026-08-13)

`bible-references`, versión 3. Runtime `java25`, 2048 MB, timeout 30 s, en la VPC con
`bible-lambda-sg` (`sg-0127ad7198071deec`), autorizado en `bible-rds-sg`. Artefacto en
`s3://bible-references-lambda-artifacts-274869222183`.

| Medida | Valor |
|---|---|
| Arranque (fase init, una vez al publicar) | **24,5 s** — frente a 48 s en Fargate, por tener más CPU |
| Restauración de SnapStart | **997 ms** la primera, **327 ms** después |
| Invocación en caliente | **15–87 ms** |
| Memoria usada | **441 MB** de 2048 |

Los 2048 MB no son por memoria sino por CPU: en Lambda ambas van ligadas, y con 441 MB de
uso real se podría bajar, a costa de restauraciones más lentas. Al estar dentro del free
tier, el ajuste no ahorra nada hoy.

**Contraste con ECS**, donde la aplicación está siempre caliente: allí ninguna petición
paga arranque; aquí lo pagará una fracción alta, porque con ~9 peticiones reales al día
Lambda no mantendrá entornos vivos. La caché en el borde para capítulos populares es lo que
mitiga eso, y C3 lo resolvería del todo.

## BLOQUEANTE — La caché en el borde no se puede activar en el plan gratuito

Intentado el 2026-08-14. **Revertido: el comportamiento `/api/*` sigue con
`Managed-CachingDisabled`.** No hay caché en el borde.

```
aws cloudfront update-distribution ... (CachePolicyId = la propia)
InvalidArgument: Distributions with the Free pricing plan can't have the following
features: Custom cache policy
```

La distribución está en el **plan de precios gratuito de CloudFront**, que solo admite
políticas gestionadas. Y ninguna gestionada sirve para esta API:

| Política | Por qué no vale |
|---|---|
| `UseOriginCacheControlHeaders`, `...-QueryStrings` | Llevan **`host`** en la clave |
| Todas las `Managed-Amplify-*` | Llevan **`Host`** en la clave |
| `Managed-CachingOptimized` | Sin `host`, pero ignora las query strings y cachea 24 h por defecto |
| `Managed-CachingDisabled` | No cachea nada. Es la que está puesta |

### El `Host` otra vez, por la puerta de atrás

Se asoció `UseOriginCacheControlHeaders-QueryStrings` y **`/api/*` empezó a devolver 403**
(`x-amzn-ErrorType: ForbiddenException`). La causa es la misma trampa del `Host` descrita
más arriba, entrando por otro sitio:

> **Las cabeceras de la clave de caché se reenvían al origen**, se quiera o no. Elegir la
> política de caché no es solo elegir qué se cachea: es también añadir cabeceras a la
> petición al origen. Que la política de origen sea `AllViewerExceptHostHeader` no protege
> de nada si la política de caché vuelve a meter el `Host`.

**Corte de ~10 minutos** hasta detectarlo y revertir. No se pudo determinar si alcanzó a
algún usuario real: **los logs de acceso de CloudFront están desactivados**
(`Logging.Enabled: false`), así que no hay registro de las peticiones rechazadas. Activarlos
—o al menos las métricas en tiempo real— es requisito para poder responder a esa pregunta
la próxima vez.

### Cómo se gestionaría qué se cachea, el día que haya plan de pago

El mecanismo ya está construido y **funcionando en la mitad del sitio**: la SPA se sirve con
`Managed-CachingOptimized` y el origen manda. `index.html` sale `no-cache, no-store,
must-revalidate` —así que un despliegue del frontend llega al instante—, y el bundle, cuyo
nombre lleva hash, sale `public, max-age=31536000, immutable` y **acierta en el borde**
(`X-Cache: Hit from cloudfront`, `Age: 22146`). La API solo necesita llegar a ese mismo sitio.

**La regla de oro: la aplicación decide, el CDN obedece.** `DefaultTTL=0` significa «sin
cabecera del origen, no se cachea nada». Añadir un capítulo es **una línea en
`VerseCacheCondition`** —o en `WordCacheCondition` para un código Strong—, sin tocar CloudFront,
sin crear comportamientos por ruta y sin invalidar nada.

#### 1. Antes de activarla: la clave de caché está desfasada

La política whitelistea `q`, `idVerse`, `includeLXX`, `limit`. Tras los cambios del
2026-08-14 **solo dos endpoints son cacheables**, y sus parámetros son otros:

| Endpoint cacheable | Parámetros que varían la respuesta |
|---|---|
| `/api/bible/chapter/{libro}/{cap}` | `idVerse`, `includeLXX` |
| `/api/strongs/{code}/stats` | **ninguno** — la ruta determina la respuesta entera |

`q` y `limit` son ahora peso muerto: `/search` sale `no-store` y no puede cachearse. No hacen
daño, pero conviene quitarlos para que la política describa la realidad —y actualizar su
`Comment`, que todavía dice «clave con X-Client-Origin, q y limit»—.

#### 2. La regla que nunca hay que romper

> **Todo lo que haga variar la respuesta tiene que estar en la clave de caché, o el endpoint
> tiene que salir `no-store`. No hay tercera opción.**

Y hay una trampa concreta: **CloudFront ignora `Vary`.** La aplicación emite hoy
`Vary: Origin` y dos cabeceras `Vary` más de CORS, y **no sirven de nada** para el borde. Lo
único que define la clave es la política. Por eso `X-Client-Origin` está whitelisteada de forma
explícita: sin ella, la respuesta 403 de un bot y la 200 de un usuario compartirían entrada.

El procedimiento al marcar un endpoint nuevo como cacheable es mecánico: listar sus entradas
—ruta, query strings, cabeceras—, comprobar que todo lo que no va en la ruta está en la clave,
y si algo falta, o se añade a la política o el endpoint se queda en `no-store`. Es exactamente
el razonamiento por el que `/api/strongs/{code}/details` salió `no-store`: depende de
`transliteratedWord` y `translatedWord`, que **no** están en la clave.

#### 3. Qué capítulos meter en la lista — y la dependencia que se pasa por alto

La lista actual son **conjeturas**, no medidas: Génesis 1/2/3, Mateo 1, Juan 1, Juan 3 y
Apocalipsis 1. Son los sospechosos habituales, elegidos por sentido común.

> **Aquí las dos funciones bloqueadas se necesitan mutuamente.** Saber qué capítulos son
> populares de verdad exige **logs de acceso**, que son PRO igual que la política de caché.
> Pasar al plan de pago da las dos cosas a la vez: la caché *y* el dato para apuntarla. Activar
> la caché sin mirar los logs sería seguir adivinando, solo que más caro.

El procedimiento, una vez haya logs: agrupar las peticiones a `/api/bible/chapter/*` por ruta,
ordenar por frecuencia, y llevar el top-N al constructor de `VerseCacheCondition`.

#### 4. Por qué la lista debe seguir siendo corta

CloudFront **desaloja los objetos poco solicitados aunque no haya expirado su TTL**. Con el
tráfico actual repartido entre 1.189 capítulos, cachearlo todo daría un ratio de acierto
cercano a cero: ninguna entrada se mantendría caliente. Concentrar las peticiones en un puñado
de rutas es lo único que funciona. Un orden de magnitud razonable son **decenas de entradas,
no miles**.

#### 5. Cómo saber si está funcionando

`CacheHitRate` es una métrica adicional: requiere `aws cloudfront create-monitoring-subscription`
y tiene coste por métrica. Hay un indicador **gratis** y bastante directo: si la caché del borde
funciona, **las invocaciones de Lambda para los capítulos populares desaparecen**. Basta con
comparar `AWS/Lambda Invocations` antes y después.

#### 6. Invalidación cuando el dato cambia

El TTL de 24 h no mide la frescura del dato —el texto bíblico no cambia—, sino **cuánto se
tarda en propagar la corrección de una errata**. Si hace falta antes:

```powershell
aws cloudfront create-invalidation --id E1MTT1XP2UUMYU --paths '/api/bible/chapter/1/1*'
```

Las primeras **1.000 URLs al mes son gratis**, y un comodín cuenta como **una** URL, así que
en la práctica esto no cuesta nada.

#### 7. Lo que se gana, en números ya medidos

| Camino | TTFB |
|---|---|
| Acierto de caché en el borde | **89 ms** |
| Lambda en caliente | 295 ms |
| Lambda en frío | ~3.100 ms |

Para los capítulos de la lista, la caché **saca a Lambda del camino por completo**: el 19,3% de
arranques en frío deja de aplicarles. Es la palanca de fluidez más grande disponible, bastante
mayor que el ping de mantenimiento.

### Qué queda hecho para cuando se retome

La política propia `bible-api-honor-origin-cache-control`
(`c1f5aa73-ce63-401c-bdc5-ea6b524938b3`) **existe y se corrigió**: su clave whitelistaba
solo `idVerse` e `includeLXX`, de modo que `/search?q=Dios` y `/search?q=tierra` habrían
compartido entrada. Ahora incluye también **`q` y `limit`**. Es correcta y está lista; solo
le falta que la distribución salga del plan gratuito.

> Hoy esa colisión no se materializaba porque `/search` **no emite `Cache-Control` en
> absoluto** y `DefaultTTL=0` impide guardarlo. Conviene no depender de eso: que el
> controlador emita `no-store` explícito en la búsqueda es una línea, y quita la dependencia
> de un valor de configuración remoto.
>
> **Hecho y desplegado el 2026-08-14 (versión 8).** `BibleController.search` emite `no-store`,
> `BibleControllerTest` lo cubre y está verificado en producción. Desaparece el peor escenario
> de una política gestionada: que la respuesta de `/search?q=Dios` se sirviera a
> `/search?q=tierra` por ignorar las query strings.
>
> **Cerrado también en `StrongController`** (ver más abajo), de modo que ya no queda ningún
> endpoint de la API cuya cacheabilidad dependa de la configuración del CDN.

### Cabeceras de caché en `/api/strongs`, y por qué las dos rutas no se tratan igual

Se cerró el 2026-08-14, después de que se retirase `includeLXX` de toda la API salvo de
`/api/bible/chapter`. Ese cambio **movió el argumento en vez de eliminarlo**:

| Ruta | Parámetros que varían la respuesta | Decisión |
|---|---|---|
| `/api/strongs/{code}/stats` | **ninguno** desde que se fue `includeLXX` | Cacheable si el código está en `WordCacheCondition` |
| `/api/strongs/{code}/details` | `transliteratedWord`, `translatedWord` | **`no-store` siempre**, aunque el código sea popular |

`/stats` pasó a ser **el mejor candidato a caché de toda la API**: la ruta determina la
respuesta por completo, así que no hay forma de que dos peticiones distintas colisionen en la
clave. Es el único endpoint del que se puede decir eso.

`/details` es el caso contrario y por eso lleva `no-store` incondicional: la política
`bible-api-honor-origin-cache-control` **no lleva esos dos parámetros en la clave** —whitelist
`q`, `idVerse`, `includeLXX`, `limit`—, de modo que cachearlo serviría la respuesta de un
filtro a quien pidió otro, o la sin filtrar a quien pidió uno.

> Se dejó explícito en el controlador en vez de arreglar solo la política. El motivo es el
> mismo que con `/search`: la política es configuración remota, editable sin pasar por revisión
> de código, y la corrección tiene que sobrevivir a que alguien la cambie. **Si algún día se
> quiere cachear el detalle, el orden es: primero las query strings en la clave, después la
> línea del controlador** — nunca al revés.

La lista de códigos populares ya existía y estaba poblada: `H430`, `G5547` y `G4151 G2316`, en
`WordCacheCondition.shouldCacheWithJustOneParam`. No hubo que decidir nada nuevo, solo cablear
una decisión ya tomada a la respuesta HTTP, con la misma forma que `BibleController`.

> **Observación aparte, sin resolver.** La otra lista de esa clase, `conditions` —la variante
> de dos parámetros—, está **entera comentada** (líneas 45-48), así que
> `shouldCache(idWord, sources)` no puede devolver `true` nunca. Es código muerto hoy. No se ha
> tocado por no saber si quedó a medias o es deliberado.

## El plan gratuito de CloudFront es un solo bloqueante con dos consecuencias

Se llegó a él por dos caminos distintos y en dos sesiones distintas, lo que hizo parecer que
eran dos problemas. Es uno.

| Lo que se quería | Error |
|---|---|
| Asociar la política de caché propia | `InvalidArgument: Distributions with the Free pricing plan can't have the following features: Custom cache policy` |
| Logs de acceso, vía moderna (*standard logging v2*) | `ValidationException: You can't enable standard access log delivery for a distribution on a Free plan tier` |
| Logs de acceso, vía heredada (`DistributionConfig.Logging` → S3) | `InvalidArgument: Distributions with the Free pricing plan can't have the following features: Standard logging` |
| Acotar la regla de tasa del WAF a `/api/*` | `WAFFeatureNotIncludedInPricingPlanException` → `{"Feature": "String match statement", "RequiredPricingPlan": "PRO"}` |

> **El error del WAF es el único que dice el nombre del plan de pago: `PRO`.** Ni CloudFront
> ni la API de precios lo exponen —la familia `Fee` solo tiene IPs estáticas, certificados e
> invalidaciones—, así que este mensaje es la única pista dura que hay en toda la cuenta sobre
> qué habría que contratar. Su precio sigue sin poderse consultar por CLI.

Las dos vías de logging se probaron **a propósito**, porque son maquinaria distinta —la
heredada es anterior a la existencia de los planes— y no se podía dar por hecho que la misma
restricción cubriera ambas. Cubre ambas.

> El intento de la vía heredada exigió `update-distribution`, que es la operación que provocó
> el corte de ~10 minutos del 2026-08-14. Aquí fue inocuo: la petición se **rechaza entera**,
> así que la distribución no llega a modificarse. Verificado después del error —
> `Logging.Enabled: False`, `/api/*` sigue en `Backend-Lambda` con `Managed-CachingDisabled`,
> 3 orígenes, y la API respondiendo 200—. Aun así se guardó copia de la configuración y se
> siguió el procedimiento del runbook, que es lo que hace barato equivocarse.

**Queda preparado para el día que se salga del plan gratuito**, sin coste mientras tanto:

| Pieza | Estado |
|---|---|
| Política de caché `bible-api-honor-origin-cache-control` (`c1f5aa73-…`) | Creada y corregida; clave con `q`, `idVerse`, `includeLXX`, `limit` y **sin `Host`** |
| Bucket `bible-references-cf-logs-274869222183` | Creado en `us-east-1`, ACL de `awslogsdelivery` concedida, expiración a 30 días |
| Grupo de logs `/aws/cloudfront/bible-references` | Creado en `us-east-1`, retención 30 días |

Ambos están vacíos y **no cuestan nada**. Si se decide no salir nunca del plan gratuito,
borrarlos es un comando cada uno.

### Cuánto costaría, con el tráfico real medido

El doc venía repitiendo «~9 peticiones al día», que son las de **API**. El total de la
distribución es otro orden de magnitud:

| Medida (14 días, hasta el 2026-08-14) | Valor |
|---|---|
| Peticiones totales | **17.546** → media **1.253/día** |
| Rango diario | 511 – 3.100 (los picos del 12 y 13 son las pruebas de la migración) |
| Bytes servidos | 67 MB |

Con ~37.600 peticiones al mes y ~600 bytes por registro, los logs son **~22 MB/mes**: menos
de **0,05 USD/mes** en CloudWatch Logs. El coste de los logs no es el argumento; el plan sí.

> **Corolario que conviene no perder.** La cifra de ~9 peticiones/día es la que sostiene el
> razonamiento de que la caché en el borde tendría mal ratio de acierto —CloudFront desaloja
> objetos poco solicitados—. Ese razonamiento sigue en pie porque lo que se cachearía son
> rutas de API, pero **cualquier estimación de coste que use 9 en vez de 1.253 está mal por
> dos órdenes de magnitud**.

## Concurrencia: el techo son 10 ejecuciones, y el fallo es un 500

Descubierto el 2026-08-14 al lanzar 14 peticiones simultáneas para forzar arranques en frío:
**cuatro devolvieron 500**. No fue un fallo de la aplicación —`Errors: 0` en Lambda, y en los
logs de la función no aparece nada—, sino el límite de concurrencia de la cuenta:

```
AccountLimit.ConcurrentExecutions: 10
Lambda Throttles: 4     API Gateway 5XXError: 4     Lambda Errors: 0
```

Las cuentas nuevas arrancan en 10 en vez de los 1.000 habituales. **API Gateway traduce el
throttle de Lambda en un 500**, así que el síntoma no apunta a la causa por ningún lado.

Con ~9 peticiones al día sobra, pero conviene tenerlo presente: es un techo duro con un
modo de fallo abrupto, no una degradación. En ECS una task servía concurrencia con su pool
de hilos y no había cliff. **Pedir subida de cuota es gratis y elimina el riesgo.**

### Solicitada el 2026-08-14 — y el techo real no era la cuota

Pedida la subida de 10 a **1.000**, el valor por defecto de una cuenta normal:

    aws service-quotas request-service-quota-increase \
      --service-code lambda --quota-code L-B99A9384 --desired-value 1000
    # Id 53c3dce730df47698089ee4179c63607nkJbc8TM
    # Status: PENDING -> CASE_OPENED (caso de soporte 178675644300495)

Al comprobar qué pasaría si se concede apareció que **la cuota de Lambda no es el primer
techo del sistema: lo es `max_connections` de RDS**, y está mucho más bajo.

| Pieza | Valor |
|---|---|
| `max_connections` (`default.postgres17`) | `LEAST({DBInstanceClassMemory/9531392},5000)` |
| `db.t4g.micro`, 1 GiB → resultado | **~112**, menos ~3 reservadas para superusuario → **~109 útiles** |
| Pool de Hikari por entorno de ejecución | **10** — el defecto, y `minimumIdle` también vale 10 |
| Pool de la task de ECS | **10** más, mientras el servicio esté arriba |

Con la cuota en 10: `10 entornos × 10 + 10 de ECS = 110`, es decir **ya justo en el techo**.
No ha estallado porque con ~9 peticiones al día la concurrencia real nunca se acerca; la
prueba de 14 peticiones simultáneas del 2026-08-14 fue lo más cerca que ha estado.

Con la cuota en 1.000, el mismo cálculo da **10.000 conexiones potenciales** y el límite se
cruza alrededor del **undécimo entorno concurrente**. Es decir:

> **Subir la cuota sin acotar el pool no elimina el modo de fallo: lo empeora.** Se cambia
> `Throttles` → 500 de API Gateway, que es contable y aparece en las métricas, por
> `FATAL: too many clients already`, que además **golpea a ECS**, porque comparten instancia
> de RDS. El fallo dejaría de estar contenido en la arquitectura nueva y se llevaría por
> delante la vía de vuelta, que es lo único que hace barata la fase B.

### Por qué el pool de una Lambda puede ser diminuto, pero no 1

Un entorno de ejecución de Lambda atiende **una petición a la vez**, así que un pool de 10
no aporta nada: son nueve conexiones inmovilizadas por entorno. Pero **1 no basta**:

`BibleService.getChapter` y `StrongService` llaman a `OutboxRecorder.record`, que es
`@Transactional(propagation = REQUIRES_NEW)`, **desde dentro** de su propio
`@Transactional(readOnly = true)`. La transacción interna pide una segunda conexión mientras
la externa retiene la primera. Con `maximumPoolSize=1` eso no es lentitud: es un
**interbloqueo** hasta agotar el `connectionTimeout`, en cada petición que registre evento.

Valores propuestos, solo para la función:

    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = 3   # 2 en uso + 1 de margen
    SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE     = 1

No hace falta tocar código: el *relaxed binding* de Spring Boot ya mapea esos nombres a
`spring.datasource.hikari.*`. Y al ir en el entorno de la función, **ECS conserva su pool de
10**, que allí sí se aprovecha porque la aplicación está caliente y sirve concurrencia con
su pool de hilos.

Con 3 por entorno: `40 entornos × 3 + 10 de ECS = 130`. Sigue por encima de 109, así que el
pool acotado **no basta por sí solo** — hace falta además topar la función.

### El orden obligado: la cuota primero, el tope después

La concurrencia reservada es la forma de acotar la función con independencia de la cuenta,
pero **no se puede poner todavía**:

```
"ConcurrentExecutions": 10,
"UnreservedConcurrentExecutions": 10
```

AWS exige dejar **100 de concurrencia sin reservar** en la cuenta, de modo que con el límite
en 10 no hay margen para reservar nada. (Regla documentada por AWS; no se ha comprobado
contra la API para no dejar la función topada por debajo de 10 mientras se prueba.)

De ahí la secuencia, que **no se puede reordenar**:

| # | Paso | Estado |
|---|---|---|
| 1 | Pedir la cuota (10 → 1.000) | ✅ **concedida el 2026-08-14** (`CASE_CLOSED`) |
| 2 | Acotar el pool de Hikari en la función (3/1) | ✅ **versión 7, alias `live` movido el 2026-08-14** |
| 3 | Reservar concurrencia a la función | ✅ **20 reservadas el 2026-08-14** — bajadas a **12** el 2026-08-25 |

### Resuelto: cuota 1.000, tope real 20

La secuencia se completó en orden. Estado final:

```
Cuota de la cuenta        : 1000
Sin reservar              :  980
Reservado a la función    :   20   <- el tope que importa
Funciones en la cuenta    :    1
```

**La cuota de la cuenta ya no es el límite operativo; la reserva sí.** La función no puede
pasar de 20 ejecuciones simultáneas haga lo que haga el resto de la cuenta, y como solo hay
una función, esos 980 restantes no los usa nadie.

Por qué 20 es un número cómodo, con las tres restricciones a la vez:

| Restricción | Cálculo | Margen |
|---|---|---|
| Conexiones a RDS | `20 × 3 (pool) + 10 (ECS) = 70` | **~109 útiles** → sobra |
| Concurrencia real observada | pico de **1** en tráfico real | 20× de holgura |
| Tope de gasto ante abuso | 20 concurrentes es acotable | vs. 1.000 sin reserva |

Y el modo de fallo vuelve a ser el bueno: al superarse, Lambda emite `Throttles` —contable y
visible en métricas— en vez de que PostgreSQL responda `too many clients already`, que además
habría arrastrado a ECS por compartir instancia.

> **Bajar la cuota de la cuenta de 1.000 no hace falta y no es trivial.** Service Quotas solo
> tramita subidas; bajarla exige caso de soporte. La reserva consigue lo mismo, es inmediata y
> se revierte con un comando:
>
> ```powershell
> aws lambda delete-function-concurrency --function-name bible-references
> ```

> **Nota de método.** El paso 2 exige **publicar versión nueva y mover el alias `live`**: las
> variables de entorno están versionadas y el snapshot de SnapStart es por versión, así que
> cambiar `$LATEST` no afecta a lo que sirve tráfico. El código no cambia —solo el entorno—,
> y la vuelta atrás es la de siempre, `update-alias` a la versión anterior.

### Paso 2 aplicado — versión 7 (2026-08-14)

`update-function-configuration` **reemplaza el mapa de entorno entero**, no fusiona. Había 4
variables (`ORIGIN_VERIFY_SECRET`, `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`) y enviar solo
las dos nuevas habría borrado las credenciales de base de datos y el secreto de origen, con
la función arrancando y fallando en la primera consulta. Se leyó el mapa actual, se le
añadieron las dos claves y se envió el conjunto de 6 por `file://`, sin que los valores
pasaran por la línea de comandos ni por pantalla —el fichero temporal se borra acto seguido—.
Es una precaución que aquí importa el doble: ambos secretos **ya están pendientes de rotar**
por haber salido en trazas de la CLI (tarea 15).

Verificación por el dominio real, con `X-Client-Origin` en todas:

| Petición | Resultado |
|---|---|
| `/api/bible/chapter/1/1` (primera, en frío) | 200 en **4,97 s** |
| `/api/bible/chapter/1/1` (×3, en caliente) | 200 en **0,57 – 0,68 s** |
| `/api/bible/chapter/43/3` | 200, 25 KB |
| `/api/bible/search?q=Dios` | 200, **854 KB** — el límite de 500 versículos sujeta lo que antes eran 5,13 MB |
| `/api/bible/search?q=tierra` | 200, 720 KB |
| `/api/strongs/H430/stats` | 200, 8 KB |

Los tiempos llevan el suelo de ~1,2 s de TCP+TLS del portátil descrito más arriba, así que
no son comparables con los 295 ms medidos reutilizando conexión.

Lo que importa de esa lista es que **la ruta del outbox pasa con el pool acotado**. Era el
riesgo concreto de bajar el pool y por eso se dejó en 3 y no en 1.

En los logs, el ciclo de CRaC funciona igual que antes del cambio:

```
o.s.b.j.HikariCheckpointRestoreLifecycle : Suspending Hikari pool
o.s.b.j.HikariCheckpointRestoreLifecycle : Evicting Hikari connections
o.s.b.j.HikariCheckpointRestoreLifecycle : Resuming Hikari pool
```

> **Lo que esta verificación NO prueba.** Que el pool valga 3 y no 10 **no se observa en
> ningún sitio**: Hikari no registra su configuración a nivel INFO, el actuator solo expone
> `health`, y a RDS no se llega desde el portátil porque la instancia ya no tiene IP pública.
> Un nombre de variable mal escrito se ignoraría **en silencio** y todo lo de arriba saldría
> igual de verde. La confianza está en el *relaxed binding* de Spring Boot, que es estándar,
> no en una comprobación. Cuando se aplique el paso 3, la forma barata de cerrar el hueco es
> mirar `DatabaseConnections` de RDS en CloudWatch durante una ráfaga: con el pool en 3 debe
> crecer de tres en tres por entorno nuevo, no de diez en diez.

## Versión 8 — despliegue de código y barrido completo (2026-08-14)

Primer despliegue de **código** desde que el alias sirve tráfico; los anteriores desde la 6
fueron de configuración. El procedimiento, por si hay que repetirlo:

```powershell
mvn -Plambda clean package                      # 29 pruebas + shade -> 57,7 MB
aws s3 cp "$B/bible-references-lambda.jar" "$B/archivo/bible-references-lambda-v7.jar"
aws s3 cp target\bible-references-lambda.jar "$B/bible-references-lambda.jar"
aws lambda update-function-code --function-name bible-references `
  --s3-bucket bible-references-lambda-artifacts-274869222183 --s3-key bible-references-lambda.jar
aws lambda wait function-updated --function-name bible-references
aws lambda publish-version --function-name bible-references --description "..."
aws lambda wait published-version-active --function-name bible-references --qualifier 8
aws lambda update-alias --function-name bible-references --name live --function-version 8
```

**Los dos `wait` no son adorno.** `publish-version` devuelve `State: Pending` y el snapshot de
SnapStart tarda; mover el alias antes de que la versión esté `Active` es apuntar a algo que
todavía puede acabar en `Failed`, que es justo como se manifestó el error del gancho de CRaC.

### Comprobación contra el artefacto, antes de subirlo

Ninguna prueba local detecta un error de exclusión de shade —corren sobre el classpath de
Maven, no sobre el JAR—. Se abre el ZIP y se comprueban clases clave:

| Comprobación | Por qué |
|---|---|
| `jakarta/el/ExpressionFactory.class` presente | Es `tomcat-embed-el`; excluirlo con comodín rompe Hibernate Validator |
| `com/gdav/.../BibleController.class` presente | Que el código propio entró |
| `org/crac/Resource.class` presente | Sin CRaC, SnapStart deja el pool con sockets muertos |
| **0** entradas bajo `BOOT-INF/` | Esa disposición el runtime de Lambda no la sabe cargar |
| **0** entradas de `org/apache/catalina/` | Tomcat embebido excluido; lo emula el adaptador |

### Barrido de endpoints en producción

| Caso | HTTP | Tamaño | `Cache-Control` |
|---|---|---|---|
| Génesis 1 (popular) | 200 | 62 KB | `max-age=86400, public` |
| Juan 3 (popular) | 200 | 25 KB | `max-age=86400, public` |
| Génesis 40 (no popular) | 200 | 18 KB | `no-store` |
| Génesis 1 con `includeLXX=true` | 200 | 43 b | `no-store` |
| Versículo puntual (`idVerse=1`) | 200 | 1,6 KB | `no-store` |
| `search?q=Dios` | 200 | 854 KB | `no-store` |
| **`search?q=que`** | 200 | 650 KB | `no-store` |
| `search?q=tierra&limit=2000` | 200 | **2,88 MB** | `no-store` |
| `/api/strongs/H430/stats` | 200 | 8 KB | *(ninguna)* |
| `chapter/0/1` (inválido) | 400 | 247 b | *(ninguna)* |
| Sin `X-Client-Origin` | 403 | — | — |

Dos que confirman cosas medidas antes: **`q=que` devolvía 502** por superar los 6 MB y ahora
cabe en 650 KB; y `limit=2000` sale en 2,88 MB, casi clavado a los ~2,8 MB estimados al fijar
el tope, con margen de sobra sobre el límite de 6 MB.

## Versión 9 — `includeLXX` fuera y caché en `/strongs` (2026-08-14)

| Cambio | Efecto |
|---|---|
| `includeLXX` retirado de `/search` y de las dos rutas de `/api/strongs` | Solo queda en `/api/bible/chapter` |
| Cabeceras de caché en `StrongController` | `/stats` cacheable si es popular; `/details` `no-store` |

Verificado por el dominio real tras mover el alias:

| Caso | HTTP | `Cache-Control` |
|---|---|---|
| `/api/strongs/H430/stats` (popular) | 200 | `max-age=86400, public` |
| `/api/strongs/H7225/stats` (no popular) | 200 | `no-store` |
| Génesis 1 / Génesis 40 | 200 | `max-age=86400, public` / `no-store` |
| `search?q=Dios` | 200 | `no-store` |
| **`search?q=Dios&includeLXX=true`** | 200 | `no-store`, **854.431 b — idéntico a sin el parámetro** |

> **El contrato cambió en silencio, y está medido.** Spring **ignora** las query strings que no
> declara ningún `@RequestParam`: enviar `includeLXX=true` a `/search` devuelve exactamente los
> mismos bytes que no enviarlo. Un cliente que dependiera de ese parámetro **no recibe ningún
> error**, recibe resultados sin LXX como si nada. Es el modo de fallo más difícil de detectar
> que puede tener una retirada de parámetro.

### PENDIENTE — `/api/strongs/{code}/details` devuelve 404 para todo

Encontrado al verificar la versión 9, y **no es una regresión**: se comprobó invocando
directamente las versiones 8 y 9 de la función —sin tocar el alias— y **ambas devuelven 404**.

```
GET /api/strongs/H430/stats    -> 200, 8 KB   (idWord H430, ĕlôhîym, ...)
GET /api/strongs/H430/details  -> 404 {"errorCode":"RESOURCE_NOT_FOUND",
                                       "message":"Strong code not found: H430"}
```

Probado con `H430`, `G5547`, `G2316`, `H3068`, `H1` y `H7225`: **404 en todos**. El mismo
código que `/stats` resuelve sin problema. Las dos rutas usan hoy la misma lista de fuentes
(`HEBREW AT`, `TR`), así que la diferencia está en la consulta del detalle, no en el filtro.

> Por eso **no se pudo verificar en producción el `no-store` de `/details`**: nunca llega a
> devolver 200. Lo cubren las pruebas de `StrongControllerTest`, pero contra el endpoint real
> queda sin comprobar hasta que el 404 se arregle.

## Ping de mantenimiento: el problema real no era la concurrencia

Montado el 2026-08-14 tras medir qué degrada de verdad la experiencia. La conclusión desmiente
lo que parecía: **la cuota de concurrencia no tenía nada que ver.**

> **Leer antes que nada: durante casi 12 horas este ping no existió.** Todo lo que sigue
> describe su diseño correctamente, pero entre las 22:26 del 2026-08-14 y las 10:00 del
> 2026-08-15 **no se ejecutó ni una sola vez**. Ver «El ping estuvo doce horas sin dispararse»,
> más abajo, antes de sacar conclusiones de cualquier medida tomada en esa franja.

### Lo medido

| Hora (UTC) | Invocaciones | Concurrencia máx | Throttles |
|---|---|---|---|
| 08-14 14:00 | 45 | **1** | 0 |
| 08-14 15:00 | 43 | **10** | **8** |
| 08-14 16:00 | 4 | 2 | 0 |
| 08-14 18:00 – 20:00 | 19 / 27 / 37 | **1** | 0 |

Concurrencia **1** en todas las horas menos una, y esa fue la ráfaga sintética de 14
peticiones con la que se descubrió el límite. **El tráfico real nunca ha pasado de 1.**

Lo que sí duele: **35 de 181 invocaciones (19,3%) pagan arranque en frío** — ~3 s frente a
~140 ms. Y para un visitante que llega tras un rato de silencio la probabilidad es
prácticamente del 100%.

### Por qué un ping y no concurrencia aprovisionada

Tarifas reales de `mx-central-1` consultadas en la API de precios:

| Opción | Coste/mes |
|---|---|
| Aprovisionada, 2048 MB, 24/7 | **~23,00 USD** |
| Aprovisionada, solo 07:55–23:00 | ~14,18 USD |
| Aprovisionada, bajando a 1024 MB | ~11,50 USD |
| **Ping cada 5 minutos** | **~0,01 USD** |

La aprovisionada cuesta **más que toda la arquitectura objetivo**. Queda descartada por
precio. Y como la concurrencia real es 1, mantener *un* entorno caliente cubre casi todo.

### Cómo está montado

**EventBridge Scheduler** `bible-keep-warm`, que invoca **el alias `live`** —no `$LATEST` ni
una versión— con un evento de API Gateway v1 para `GET /actuator/health`.

```
Expresion : cron(0/5 10-19 * * ? *)
Zona      : America/Mexico_City
Ventana   : FlexibleTimeWindow = OFF
Destino   : arn:aws:lambda:...:function:bible-references:live
Rol       : arn:aws:iam::...:role/bible-scheduler-role
```

Tres decisiones que importan:

- **Apunta al alias**, porque es lo que tiene integrado API Gateway. Calentar otra versión
  calentaría entornos que nadie usa.
- **Va a `/actuator/health` y no a un endpoint de API** porque esa ruta está exenta de
  `OriginVerifyFilter` y de `OriginValidationFilter`. Un endpoint de `/api/*` habría exigido
  meter `X-Origin-Verify` en la configuración del schedule, en claro y legible por cualquiera
  con permiso de lectura — **un tercer sitio donde vive un secreto que ya está pendiente de
  rotar** (tarea 15). No compensaba.
- **Scheduler y no una regla clásica de EventBridge.** Se montó primero como regla clásica con
  `rate(5 minutes)` y se migró: las reglas clásicas **solo entienden UTC**, así que acotar la
  franja habría exigido traducir a mano `cron(0/5 16-23,0-1 ...)` —una expresión que cruza la
  medianoche y que nadie relee correctamente seis meses después—. Scheduler acepta
  `America/Mexico_City` directamente. Y, además, **los otros cuatro schedules del proyecto ya
  viven ahí**: tenerlos juntos significa que `aws scheduler list-schedules` los muestra todos.

Reutiliza `bible-scheduler-role`, el mismo rol de los schedules de RDS y ECS, al que se le
añadió `lambda:InvokeFunction` sobre el ARN del alias.

Para quitarlo:

```powershell
aws scheduler delete-schedule --name bible-keep-warm
# y, si no se quiere dejar el permiso suelto, quitar lambda:InvokeFunction
# de la politica inline bible-scheduler-policy
```

### La franja está atada al apagado nocturno de la base de datos

El ping **no puede correr 24/7**: entre las 23:10 y las 07:40 la instancia de RDS está parada.
Un ping en esa franja mantendría caliente un entorno **sin base de datos**, que es peor que no
tener ninguno.

| Hora (CDMX) | Qué pasa | Quién lo hace |
|---|---|---|
| 07:40 | Arranca RDS | `bible-rds-start` |
| **10:00** | **Primer ping** | `bible-keep-warm` |
| **19:55** | **Último ping** | `bible-keep-warm` |
| 23:00 | Para ECS (`desiredCount` a 0) | `bible-ecs-stop` |
| 23:10 | Para RDS | `bible-rds-stop` |

> **Consecuencia que conviene mirar de frente: la API no funciona de 23:10 a 07:40.** Son
> **8 horas y media al día** en las que un visitante no recibe lentitud, recibe un error. El
> ping no cambia eso ni pretende hacerlo; es una decisión de coste ya tomada, pero no aparecía
> escrita en ningún sitio.

#### Comprobado en vivo el 2026-08-14 a las 23:16 CDMX

No es una deducción. Se observó por casualidad al verificar otro cambio, seis minutos después
de que corriera `bible-rds-stop`:

```
Ahora CDMX : 2026-08-14 23:16          RDS database-2 : "stopping"
GET /api/bible/chapter/1/1  -> HTTP 504
GET /                       -> HTTP 200      (la SPA no necesita base de datos)
```

En los logs de la función, a las 05:13:34 UTC:

```
HikariPool-1 - Failed to validate connection org.postgresql.jdbc...
org.hibernate.exception.JDBCConnectionException: Unable to obtain isolated JDBC connection
  [The connection attempt failed.]
```

**Y el detalle que empeora el diagnóstico: no falla rápido, se cuelga.** La respuesta es
**504 Gateway Timeout**, no un 503 inmediato: la función se queda intentando conectar hasta
que expira el tiempo de integración. El visitante nocturno no ve un error enseguida, ve la
página girando ~30 segundos y luego un fallo. Si algún día se decide vivir con la parada
nocturna, al menos convendría **fallar rápido** —un timeout de conexión corto en Hikari— para
que la experiencia sea «esto no está disponible ahora» en vez de «esto está roto».

> Ojo al interpretar 5xx durante estas horas: **cualquier medición o alarma nocturna dará
> error por esta razón**, no por la migración. Y como la SPA sí responde 200, un monitor que
> solo compruebe la portada no se enterará nunca.

#### Corregido: de colgarse 30 s a fallar en 4 (versiones 10 y 11)

No arregla la parada nocturna —eso es una decisión de coste— pero sí **cómo se siente**.
Aprovechando que RDS estaba parada, se midió cada paso contra la función real:

| Versión | Qué lleva | Resultado con RDS parada |
|---|---|---|
| 9 | nada | **33,4 s y ninguna respuesta** — la ejecución expira, CloudFront devuelve 504 |
| 10 | `connection-timeout: 5000` (Hikari) | 13,7 s, **HTTP 500** |
| 11 | `+ connectTimeout: 2` (pgjdbc) | **8,0 s**, HTTP 500 |
| 11, por el dominio y en caliente | — | **HTTP 500 en 4,0 s** |

**La lección: `connection-timeout` de Hikari no basta, y creer que sí habría cerrado el tema en
falso.** Ese ajuste acota la espera por una conexión *del pool*; quien manda al abrir el socket
es el driver, y pgjdbc trae **10 s** por defecto. Se vio en los logs de la versión 10: la
excepción llegaba a los 11 s, no a los 5.

```
Caused by: org.postgresql.util.PSQLException: The connection attempt failed.
Caused by: java.net.SocketTimeoutException: Connect timed out
```

Los dos valores hacen cosas distintas y **hacen falta los dos**:

| Propiedad | De quién | Unidad | Qué acota |
|---|---|---|---|
| `spring.datasource.hikari.connection-timeout: 5000` | Hikari | **ms** | Esperar una conexión del pool |
| `spring.datasource.hikari.data-source-properties.connectTimeout: 2` | pgjdbc | **s** | Abrir el socket TCP |

> **Por qué 5 s y no menos en Hikari.** La primera invocación tras restaurar SnapStart tardó
> hasta **3,7 s** en las medidas de producción. Un timeout de 3 s se acercaría a ese peor caso
> y convertiría un arranque en frío lento en un error. Dentro de la VPC, abrir la conexión son
> decenas de milisegundos: 5 s siguen siendo 25-100x el coste real.

`DataSourceConfigurationTest` fija ambas cosas: que el valor sea 5.000 y que **no baje de
3.716 ms**, el peor arranque en frío medido. Existe porque una clave mal escrita en la
configuración de Hikari **no da ningún error** —Spring la ignora en silencio y el pool arranca
con los valores por defecto—, que es el mismo modo de fallo silencioso del pool de la Lambda.

> **Lo que no se tocó: `socketTimeout`.** Acotaría el caso en que la conexión se establece y
> luego el servidor deja de responder. Se dejó en infinito a propósito: `/api/bible/search`
> tarda 1,1-1,6 s de forma legítima y un valor mal calibrado mataría búsquedas buenas. El
> fallo observado es de conexión, no de lectura.

#### Hallazgo lateral: la aplicación **sí** arranca sin base de datos

Se predijo que publicar una versión con RDS parada fallaría, porque la fase de init abre una
conexión real (`HikariPool-1 - Added connection ... PgConnection`). **La predicción era falsa:**
la versión 10 se publicó `Active` con la base de datos apagada.

```
05:29:05  HikariPool-1 - Starting...
05:29:16  JDBCConnectionException: Unable to obtain isolated JDBC connection
          at com.zaxxer.hikari.pool.HikariPool.checkFailFast(HikariPool.java:576)
05:29:26  Started AWSLambda in 32.678 seconds
          INIT_REPORT Init Duration: 34617.27 ms
```

Hikari registra el fallo de `checkFailFast` y **el contexto sigue levantándose igual**. Tiene
una consecuencia práctica que conviene saber: **se puede desplegar durante la ventana nocturna**
sin esperar a las 07:40. El init tarda ~35 s en vez de ~24 s, pero termina en `Active`.

> **Lo que el ping no hace.** Mantiene **un** entorno. Si dos peticiones coinciden, la segunda
> sigue pagando arranque en frío. Y AWS puede reciclar el entorno igualmente: es una medida
> probabilística, no una garantía. Lo que sí elimina del todo el arranque en frío para los
> capítulos populares es la caché en el borde, hoy bloqueada por el plan gratuito.

### El intervalo de 5 minutos está comprobado

Hubo una alarma falsa: la primera invocación programada pagó restauración y pareció que 5
minutos era demasiado. **Era ruido propio** — justo antes se habían lanzado cuatro sondeos en
tres segundos, que crearon varios entornos a la vez y enturbiaron la medida.

Medido después en una ventana limpia, sin actividad manual:

```
04:19:09 UTC   23 ms   caliente
04:24:09 UTC   23 ms   caliente
---- frios: 0 de 2
```

Dos pings separados exactamente 5 minutos, **ambos calientes y a 23 ms**. El entorno sobrevive
el intervalo, así que no hace falta bajarlo a 3 o 4 minutos.

> **La lección es sobre el método, no sobre el intervalo.** Medir mientras uno mismo está
> tocando el sistema produce datos que parecen resultados. Los cuatro sondeos manuales no eran
> parte del experimento y aun así lo decidieron. La medición buena fue la que no tenía a nadie
> dentro.

> **Atención al releer esta medida.** Las 04:19 y 04:24 UTC son las **22:19 y 22:24 CDMX**, y
> `bible-keep-warm` no existía todavía —se creó a las 22:26—. Esos dos pings los emitía la
> **regla clásica** de EventBridge con `rate(5 minutes)`, que se borró al migrar a Scheduler.
> La medida sigue siendo válida como prueba de que un entorno sobrevive 5 minutos de reposo,
> pero **no prueba nada sobre el schedule que hay hoy**.

### El ping estuvo doce horas sin dispararse (2026-08-15)

Encontrado al investigar por qué la función seguía arrancando en frío pese al ping. La sospecha
de partida era que `/actuator/health` devolvía 503 y por eso «no servía» para calentar. **Era
una hipótesis equivocada**, y la causa resultó ser mucho más simple:

> **`bible-keep-warm` se creó a las 22:26 CDMX del 2026-08-14 y su ventana es 10:00–19:55.**
> Se creó *después* de que cerrara la ventana de ese día, así que su primera ejecución posible
> era a las 10:00 del día siguiente. Entre medias no había ping: la regla clásica que lo hacía
> 24/7 ya estaba borrada.

Lo que lo demuestra, en orden de contundencia:

| Comprobación | Resultado |
|---|---|
| `AWS/Scheduler InvocationAttemptCount`, grupo `default`, 26 h | **1–2 por hora** y solo en tres buckets: 07:52, 22:52 y 06:52 → son `bible-rds-start`, `bible-ecs-stop` y `bible-rds-stop`. Un ping cada 5 min daría **12/hora** |
| `TargetErrorCount`, `InvocationDroppedCount`, `TargetErrorThrottledCount` | **vacíos** — no es que el destino falle, es que no se intenta |
| `aws events list-rules` | **ninguna regla** — la clásica con `rate(5 minutes)` se borró al migrar |
| `Invocations` del alias `live`, 01:00–08:52 CDMX | **0** |
| Primera invocación programada de la historia | **10:00:11 CDMX del 2026-08-15**, en frío (`Restore Duration: 789 ms`, 1.569 ms en total) |

#### Y una vez disparado, funciona

Verificado el mismo 2026-08-15, ya con el schedule corriendo:

```
10:00:11   1.569 ms   FRIO       primera de la historia; no habia entorno vivo
10:05:13   1.500 ms   FRIO       <- contaminada: ver abajo
10:10:11      10,9 ms  caliente
10:15:11      18,9 ms  caliente
```

Los dos últimos van separados **exactamente 5 minutos** y ninguno trae `Restore Duration`: el
entorno sobrevive el intervalo. Confirma con el schedule actual lo que el 2026-08-14 solo se
había comprobado con la regla clásica.

> **El frío de las 10:05 fue ruido propio, y se reconoce como tal en vez de explicarlo.** Entre
> los dos pings hubo un `update-function-code` a las 16:00:39 UTC y un `publish-version` a las
> 16:02 —el despliegue de la versión 12—. Tomar esa medida por buena habría llevado a bajar el
> intervalo a 3 minutos para arreglar un problema inexistente. **Es la tercera vez en dos días
> que una medida tomada con las manos dentro del sistema apunta en la dirección contraria**; la
> regla práctica que sale de aquí es no medir el ping en los 10 minutos siguientes a tocar la
> función.

**Por qué el 503 no tenía nada que ver.** Lo que mantiene vivo un entorno es **la invocación**,
no el código de estado de la respuesta: un 503 calienta exactamente igual que un 200, y el
endpoint sigue evaluando todos los indicadores —incluido el de base de datos— que es lo que
reanima el pool de Hikari. Además, si la invocación hubiera fallado de verdad, aparecería en
`TargetErrorCount`. El permiso, comprobado, estaba bien: `bible-scheduler-role` tiene
`lambda:InvokeFunction` sobre `...:function:bible-references:live`.

> **La lección de método, otra vez la misma y desde el otro lado.** El 2026-08-14 se creyó
> medido algo que era ruido propio; el 2026-08-15 se creyó funcionando algo que **nunca se
> había ejecutado**. En ambos casos faltó la misma comprobación de dos comandos: *¿el
> mecanismo ha corrido, cuántas veces y a qué hora?* Con `InvocationAttemptCount` se ve en
> diez segundos. Verificar que una automatización **existe** no es verificar que **corre**;
> es la misma trampa que el `apigatewayv2 get-apis` que respondía sin error.

#### La ventana es más estrecha de lo que la base de datos permite

Al margen del fallo anterior, la franja elegida deja fuera horas en las que la API sí funciona:

| Franja (CDMX) | RDS | Ping | Qué paga el visitante |
|---|---|---|---|
| 07:40 – 10:00 | arriba | **no** | arranque en frío, ~3 s |
| 10:00 – 19:55 | arriba | sí | ~140 ms |
| 19:55 – 23:10 | arriba | **no** | arranque en frío, ~3 s |
| 23:10 – 07:40 | **parada** | no | error (500 en ~4 s desde la versión 11) |

Son **5 h 30 min al día con la base de datos disponible y sin ningún entorno caliente**, y la
franja de tarde-noche es plausiblemente la de más lectura. El coste de cerrarla es
despreciable: pasar de 10 a 15 horas son ~5.400 invocaciones al mes de ~150 ms a 2.048 MB,
**muy dentro del *free tier*** (400.000 GB·s y 1 M de peticiones). `cron(0/5 8-22 * * ? *)`
cubre de 08:00 a 22:55 sin invadir la parada nocturna.

Encontrado el 2026-08-14 al revisar los schedulers para acotar el ping. **Los dos schedules de
ECS no están en el mismo estado:**

| Schedule | Expresión (CDMX) | Estado |
|---|---|---|
| `bible-ecs-stop` | `cron(0 23 * * ? *)` | **ENABLED** |
| `bible-ecs-start` | `cron(55 7 * * ? *)` | **DISABLED** |

Es decir: **el servicio ECS se para cada noche a las 23:00 y ya no vuelve a arrancar solo.**
Hoy hay 1 task corriendo porque alguien la levantó a mano.

Esto rompe dos cosas que este documento y el runbook daban por ciertas:

- `runbook-vuelta-atras-lambda.md`, paso 0, dice *«Entre las 23:00 y las 07:55 el servicio ECS
  está a 0 tasks»*, dando a entender que a las 07:55 vuelve. **No vuelve.** Quien siga el
  runbook a las 10 de la mañana encontrará 0 tasks y no lo esperará.
- La sección «Qué mantiene barata la fase B» pide explícitamente *«No parar el servicio ECS ni
  bajar `desiredCount` de forma permanente»*. Con el arranque desactivado, eso es justo lo que
  está ocurriendo cada noche.

No es catastrófico —`update-service --desired-count 1` lo resuelve en ~1 minuto— pero
**convierte la vuelta atrás en un procedimiento con un paso manual obligatorio a cualquier hora
del día**, no solo de madrugada. Hay que decidir una de dos:

1. **Reactivar `bible-ecs-start`** si se quiere conservar la red de seguridad tal como está
   descrita. Cuesta lo que cueste Fargate en horario diurno.
2. **Dejarlo desactivado** —es una decisión de coste razonable— y entonces **corregir el
   runbook** para que el paso 0 diga que ECS estará a 0 salvo intervención manual.

Lo que no vale es dejarlo como está: un runbook que describe una realidad que ya no existe es
peor que no tenerlo, porque se descubre en el momento de usarlo.

## PENDIENTE — `/actuator/health` devuelve 503 en Lambda y 200 en ECS

Descubierto al montar el ping, y **no lo causa la migración de hoy**: es una diferencia de
entorno, no de código.

```
Lambda (alias live)  GET /actuator/health  -> 503 {"groups":["liveness","readiness"],"status":"DOWN"}
ECS (mismo endpoint) GET /actuator/health  -> 200  (target group `bible-tg` = healthy, matcher 200)
```

Los grupos por separado están **UP**:

| Sonda | Resultado |
|---|---|
| `/actuator/health/liveness` | 200 `{"status":"UP"}` |
| `/actuator/health/readiness` | 200 `{"status":"UP"}` |
| `/actuator/health/db`, `/diskSpace`, `/ping` | 404 — `show-details: never` no expone componentes |

Es decir: **algún indicador fuera de los grupos liveness/readiness está en DOWN**, y
`show-details: never` impide saber cuál. La hipótesis más probable es `diskSpace`:
`DiskSpaceHealthIndicator` mide el espacio libre del directorio de trabajo, y en Lambda ese
directorio es `/var/task`, **de solo lectura**. Encaja con que ECS —con sistema de ficheros
escribible— dé 200 con el mismo código. **No está confirmado**: confirmarlo exige poner
`management.endpoint.health.show-components: always` y volver a desplegar.

Consecuencias, por orden de importancia:

- **La API funciona perfectamente**: todos los endpoints responden 200. Esto es un indicador
  que miente, no una caída.
- **No hay señal de salud utilizable para la función.** Si algún día se monta una alarma sobre
  ese endpoint, saltaría siempre.
- El ping no se ve afectado: un 503 mantiene el entorno caliente igual, y el endpoint sigue
  evaluando todos los indicadores —incluido el de base de datos—, que es lo que interesa para
  mantener vivo el pool de Hikari.

### RESUELTO — era `diskSpace`, confirmado y corregido (2026-08-15, versión 12)

La hipótesis era correcta. Se confirmó **sin arriesgar tráfico**: se publicó la versión 12 con
el arreglo y se invocó **directamente esa versión** —no el alias, que siguió en la 11— con el
mismo evento de API Gateway que usa el ping.

```
GET /actuator/health (invocación directa de la versión 12)  -> 200
{"status":"UP","components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},
 "livenessState":{"status":"UP"},"ping":{"status":"UP"},
 "readinessState":{"status":"UP"},"ssl":{"status":"UP"}},"groups":["liveness","readiness"]}
```

Cambiar **únicamente** la ruta que mide `diskSpace` convierte el 503 en 200, lo que aísla la
causa sin dejar lugar a otra explicación. El arreglo, en `application.yaml`:

```yaml
management:
  endpoint:
    health:
      show-details: never
      show-components: always      # nombres y estado, sin el mapa de detalles
  health:
    diskspace:
      path: /tmp                   # el unico directorio escribible de Lambda (512 MB)
```

Dos decisiones que conviene entender:

- **`/tmp` en vez de desactivar el indicador.** Desactivarlo habría hecho desaparecer el 503
  igual, pero a cambio de perder la comprobación en ECS, donde sí mide algo. `/tmp` existe en
  ambos entornos, así que el indicador vuelve a medir algo real en los dos sin bifurcar la
  configuración por entorno.
- **`show-components: always` se queda**, y no era solo para diagnosticar una vez. Sin él, el
  endpoint devuelve `503` a secas y averiguar qué falla exige **desplegar una versión solo para
  mirarlo**, que es justo lo que hubo que hacer aquí. No expone datos: `show-details: never`
  sigue ocultando el mapa de detalles —producto de base de datos, bytes libres—, y lo que se ve
  son nombres de indicador y su estado. Importa porque `/actuator/health` está exento de
  `OriginVerifyFilter` y es alcanzable por la URL de ejecución de API Gateway.

Un hallazgo lateral de la respuesta completa: en Spring Boot 4.1 hay un indicador **`ssl`** que
no estaba en la lista de sospechosos. Está UP, pero conviene saber que existe — comprueba la
caducidad de los certificados del keystore y **puede pasar a DOWN por su cuenta** el día que se
configure alguno.

`HealthConfigurationTest` fija la ruta leyendo el YAML real, por el mismo motivo que
`DataSourceConfigurationTest` existe para Hikari: **una clave mal escrita aquí no da ningún
error**. Spring la ignora, el indicador vuelve a `user.dir` y el síntoma sería otra vez un 503
sin explicación, en un valor que no se puede observar desde producción.

#### Barrido tras mover el alias a la 12

Por el dominio real, con `X-Client-Origin` en todas:

| Caso | HTTP | Tamaño | `Cache-Control` | Tiempo |
|---|---|---|---|---|
| Génesis 1 (popular) | 200 | 62 KB | `max-age=86400, public` | **4,1 s** — en frío, esperado tras mover el alias |
| Juan 3 (popular) | 200 | 25 KB | `max-age=86400, public` | 1,8 s |
| Génesis 40 (no popular) | 200 | 18 KB | `no-store` | 0,8 s |
| `search?q=Dios` | 200 | 854 KB | `no-store` | 2,0 s |
| `/api/strongs/H430/stats` | 200 | 8 KB | `max-age=86400, public` | 1,4 s |
| `/actuator/health` (alias `live`) | **200** | — | — | `status: UP`, seis indicadores |

Mover el alias **recicla los entornos calientes**: la primera petición después paga arranque en
frío aunque el ping esté corriendo. Se ve en los 4,1 s de la primera fila frente a los 0,8–2,0 s
de las siguientes. Conviene contarlo al planificar un despliegue, y no confundirlo con una
regresión de latencia.

> **Lo que este arreglo NO era.** Se llegó a él investigando por qué el ping no mantenía la
> función caliente, sospechando que un health check que devuelve 503 no serviría para calentar.
> **Esa relación no existe**: lo que mantiene vivo un entorno es la invocación, no el código de
> estado. El 503 era un problema real —una señal de salud inservible— pero independiente. La
> causa del otro problema está en la sección siguiente.

## Protección frente a abuso y DDoS — estado real (2026-08-14)

Levantado al preguntarse si la aplicación está protegida. **Conviene empezar por lo que no
hay: en ningún momento se ha detectado un ataque ni tráfico de robots.** Lo único observado
es que el total de la distribución son ~1.253 peticiones/día frente a las ~9 de API que cita
este documento, y esa diferencia **no está caracterizada**: sin logs de acceso no se puede
saber si son rastreadores, el propio frontend pidiendo estáticos, o cualquier otra cosa.

### Lo que sí hay

| Capa | Estado |
|---|---|
| **AWS Shield Standard** | Activo y gratuito con CloudFront. Cubre L3/L4 —inundaciones SYN, reflexión UDP— |
| **WAF** | Web ACL `CreatedByCloudFront-228216dd` adjunta, acción por defecto `Allow` |
| — `AmazonIpReputationList` | Bloquea IPs con mala reputación conocida |
| — `CommonRuleSet` | Patrones comunes de explotación |
| — `KnownBadInputsRuleSet` | Entradas maliciosas conocidas |
| **Protección del origen** | `OriginVerifyFilter` + API Gateway: no se puede esquivar el CDN |
| **API Gateway** | Sin throttling de stage; hereda el de cuenta: **2.500 rps, ráfaga 1.250** |
| **Shield Advanced** | **No** contratado (`ResourceNotFoundException` al consultar la suscripción) |

Curiosamente **WAF tampoco aparece en la factura**, igual que CloudFront. Encaja con que el
plan gratuito incluya estas protecciones, pero no se ha confirmado.

### El hueco que había: ningún límite de tasa

Ninguno de los tres grupos gestionados es una regla *rate-based*. **No existía rate limiting en
ninguna capa** salvo el techo de 2.500 rps de API Gateway, que es un límite de cuenta, no una
defensa. Consecuencia concreta:

> `/api/bible/search` es el objetivo perfecto para abuso: la consulta hace `unaccent` y `~*`
> **sobre toda la Biblia**, tarda 1,1–1,6 s y devuelve hasta 2,88 MB. Petición barata,
> respuesta cara. Una sola IP podía repetirla al ritmo que quisiera sin que nada la frenara.

### Regla `RateLimitPorIP`, añadida el 2026-08-14

```
[3] RateLimitPorIP   Action=Block
    Limit               : 150
    EvaluationWindowSec : 300      <- no se puede cambiar
    AggregateKeyType    : IP
    equivale a          : 30 peticiones/minuto de media
```

> **La ventana de 5 minutos es obligatoria.** Al intentar expresar «30 por minuto» como
> `Limit=30, EvaluationWindowSec=60`, AWS respondió:
>
> ```
> "Feature": "Rate-based statement with evaluation window other than default (five minutes).",
> "RequiredPricingPlan": "DISALLOWED"
> ```
>
> Es la **cuarta** restricción del plan encontrada, y la única marcada `DISALLOWED` en vez de
> `PRO`: no se compra, simplemente no está. El equivalente es `Limit = objetivo × 5`, con una
> diferencia real de comportamiento: **la ventana de 5 minutos tolera ráfagas**. Un cliente
> puede gastar las 150 en diez segundos y quedar bloqueado el resto del periodo, en vez de
> repartirlas a 30 por minuto. Para frenar abuso sostenido da igual; para ráfagas cortas es
> más permisivo de lo que sugiere la cifra.

La ACL pasa de 925 a **927 WCU** y las tres reglas gestionadas quedan intactas en las
prioridades 0-2. Verificado tras aplicarla: API y frontend siguen en 200.

**No está acotada a `/api/*`, y no por elección.** El intento inicial llevaba un *scope-down*
por ruta y AWS lo rechazó: el `String match statement` exige plan **PRO**. La regla de tasa en
sí **sí** entra en el plan gratuito; lo que no entra es poder decir a qué se aplica.

Eso obliga a un umbral más alto y más burdo del que se querría, porque el contador incluye
**todo**: HTML, JS, CSS, fuentes e imágenes además de las llamadas a la API. Por qué 1.000
cada 5 minutos es defendible pese a ello:

El umbral pasó por 1.000 → 300 → **150 (30/min)** en la misma sesión. La medida del coste real
de una carga de página es lo que permitió bajarlo:

| Referencia | Valor |
|---|---|
| Peticiones por carga en frío de la SPA | **5** — `index.html` (584 b), un bundle JS, favicon, logo, manifest |
| Peticiones por capítulo, ya cargada la SPA | **~1** (enrutado en cliente, el bundle queda en caché del navegador) |
| Umbral de la regla | **150 desde una sola IP en 5 minutos** (30/min) |
| Presupuesto tras una carga en frío | 145 peticiones en esos 5 minutos |
| Lo que haría falta para dispararla | ~145 capítulos en 5 min → **uno cada 2 segundos sostenido** |
| Tráfico total del sitio, para escala | ~1.253 peticiones/**día**, entre todos |

> **Dónde está ahora el riesgo de falso positivo.** Con 150 el margen ya no es enorme. El
> escenario que más se acerca es un lector que abra un capítulo y **pinche varias palabras**:
> cada código Strong es otra llamada. Leyendo tres capítulos y consultando diez palabras en
> cada uno salen ~33 peticiones en pocos minutos — todavía lejos de 150, pero la holgura ya no
> es de dos órdenes de magnitud. Si alguna vez se enriquece el frontend con más llamadas por
> vista, **este número hay que revisarlo**, y conviene mirar `BlockedRequests` antes de darlo
> por bueno.

**La medida es lo que permitió bajar el umbral con tranquilidad.** El riesgo de acotar tanto
era que la regla cuenta *todo*, estáticos incluidos; si la SPA hubiera sido de las que piden
cuarenta ficheros por página, 300 habría estado peligrosamente cerca del uso legítimo. Con
cinco, no lo está.

Que el margen sea amplio importa porque **un bloqueo indebido sería invisible**: sin logs de
acceso no hay forma de saber a quién se rechazó.

> **Cómo vigilarla, ya que no hay logs.** La regla emite métrica propia. Si alguna vez
> dispara, conviene mirarlo antes de suponer que fue un atacante:
>
> ```powershell
> aws cloudwatch get-metric-statistics --namespace AWS/WAFV2 --metric-name BlockedRequests `
>   --dimensions Name=WebACL,Value=CreatedByCloudFront-228216dd Name=Rule,Value=RateLimitPorIP `
>   Name=Region,Value=CloudFront --start-time <ini> --end-time <fin> --period 3600 `
>   --statistics Sum --region us-east-1
> ```

Para revertirla basta con volver a poner las tres reglas originales; la copia de la ACL previa
se tomó antes de tocar nada. Y si algún día se pasa a PRO, lo primero es **acotarla a `/api/*`
y bajar el umbral**: con el *scope-down* puesto, un límite de ~100 cada 5 minutos sería
razonable y mucho más efectivo que este.

### La cuota de concurrencia es hoy un cortacircuitos accidental

Con el límite en **10 ejecuciones concurrentes**, una inundación se autolimita: Lambda
descarta el exceso y el daño queda acotado. Es feo —el exceso sale como 500— pero contiene.

**La subida a 1.000 solicitada el 2026-08-14 elimina esa contención.** Con 1.000, lo primero
que cae no es Lambda sino **RDS**: con el pool en 3 por entorno, los ~109 conexiones útiles se
agotan alrededor del **entorno 36**. Y el coste deja de ser teórico —a 2.048 MB y ~300 ms por
invocación, una hora de saturación sostenida se cuenta en cientos de dólares—.

> **Esto no es un argumento para no subir la cuota**, sino para no subirla sola.

**Cerrado el 2026-08-14.** La cuota se concedió y se reservaron **20** de concurrencia a la
función el mismo día, así que la ventana de exposición no llegó a quedar abierta. El tope de
gasto ante un abuso vuelve a existir —20 concurrentes, no 1.000— y las conexiones a RDS quedan
en `20 × 3 + 10 = 70` de ~109 útiles.

Lo que **no** resuelve la reserva: un atacante puede seguir saturando esos 20 y dejar al resto
sin servicio. Convierte un problema de coste y de base de datos en uno de disponibilidad, que
es preferible pero no es lo mismo que estar protegido. Para eso sigue haciendo falta la regla
*rate-based*.

### Estado tras la sesión del 2026-08-14

| # | Medida | Estado |
|---|---|---|
| 1 | Reservar concurrencia (20) | ✅ hecho — tope de gasto y de conexiones a RDS |
| 2 | Regla *rate-based* en el WAF | ✅ hecho — 1.000/IP cada 5 min, sin acotar por ruta |
| 3 | Logs de acceso | ❌ bloqueados por el plan gratuito |
| 4 | Bot Control | ⏸️ de pago; solo si 1-2 resultan insuficientes |

Queda por confirmar si añadir la regla cambia la factura de WAF, que hoy es **0**. Merece la
pena mirarlo en el próximo ciclo de facturación:

```powershell
aws ce get-cost-and-usage --time-period Start=2026-09-01,End=2026-10-01 --granularity MONTHLY `
  --metrics UnblendedCost --group-by Type=DIMENSION,Key=SERVICE --region us-east-1
```

> **Lo que sigue sin cubrirse.** Con la reserva en 20, un atacante ya no puede disparar el
> gasto ni tumbar RDS, pero **sí puede saturar esos 20 y dejar sin servicio a los demás**. Se
> ha convertido un problema de coste en uno de disponibilidad: mejor, pero no resuelto. Y la
> regla de tasa, al no poder acotarse a `/api/*`, tiene el umbral tan alto que solo frena
> abusos groseros.

### El hueco que quedaba: el WAF no cubre el punto de entrada real (2026-08-25)

> Las tres capas de limitación de tasa resultantes están documentadas por separado en
> **`docs/rate-limiting-lambda.md`**, con umbrales, verificación y reversión.

La regla `RateLimitPorIP` cuelga de **CloudFront**. El backend cuelga de **API Gateway**, que
estaba expuesto sin ninguna defensa propia. Comprobado el 2026-08-25 con una petición a
`m9zcdngc1m.execute-api.mx-central-1.amazonaws.com/prod/api/bible/chapter/1/1`:

```
HTTP/1.1 403 Forbidden
Content-Type: text/plain; charset=UTF-8
Content-Length: 9        ->  "Forbidden"
```

Ese cuerpo es el de `OriginVerifyFilter`, no el de API Gateway: **el 403 lo emitió la Lambda ya
invocada**. Las métricas lo confirman —baseline de 1 invocación/5 min por el ping, bucket de
las 22:45 UTC en 4 tras las pruebas—. Es decir: **una petición rechazada cuesta lo mismo que
una servida**, y el límite de 150/IP del borde no ve nada de ese tráfico.

> **Corrección a lo que decía este documento.** El javadoc de `OriginVerifyFilter` da por hecho
> que es una *HTTP API*. No lo es: `apigatewayv2 get-apis` devuelve vacío y la API aparece en
> `apigateway get-rest-apis` como **REST API (v1)**. Importa porque una REST API **sí** admite
> throttling de stage y asociar un Web ACL regional; la HTTP API no ofrece lo mismo.

Estado encontrado en el stage `prod`: `methodSettings: {}` (sin throttling, heredaba los
2.500 rps / ráfaga 1.250 de cuenta), sin `webAclArn`, `policy: null`, endpoint `REGIONAL`.

### Throttling de stage, aplicado el 2026-08-25

```
rateLimit  : 10 rps sostenido
burstLimit : 20
alcance    : */* (todos los métodos del stage prod)
```

Por qué 10/20: el tráfico legítimo de API son ~9 peticiones/día, y el pico realista —un lector
abriendo un capítulo y pinchando diez palabras— son ~11 peticiones en un minuto (0,2 rps). El
límite queda dos órdenes de magnitud por encima del uso real y 250 veces por debajo del techo
de cuenta que se heredaba.

**Lo que de verdad aporta:** con 12 concurrentes y ~300 ms por invocación, la función satura
alrededor de 40 rps (8 rps si todo fuera `/search`, que tarda 1,1–1,6 s). Al poner el techo en
10, **API Gateway responde 429 sin invocar la Lambda** en vez de dejar que Lambda emita
`Throttles` que salen como 500. El modo de fallo pasa a ser barato y semánticamente correcto.

Limitación que conviene tener presente: **el throttling de stage es global, no por IP**. No
distingue a un abusador de la suma de usuarios legítimos. Por eso no sustituye a la regla del
WAF, la complementa: el borde limita por IP, el stage pone el techo absoluto.

### Concurrencia reservada bajada de 20 a 12 (2026-08-25)

Efecto sobre RDS: las conexiones máximas pasan de `20 × 3 + 10 = 70` a `12 × 3 + 10 = 46` de
las ~109 útiles. Con el throttling puesto, 12 concurrentes siguen sobrando para el tráfico real.

### Reversión de ambos cambios

Estado previo verificado antes de tocar: `methodSettings: {}` y `ReservedConcurrentExecutions: 20`.

```powershell
# Quitar el throttling de stage (vuelve a heredar el límite de cuenta)
aws apigateway update-stage --rest-api-id m9zcdngc1m --stage-name prod --region mx-central-1 `
  --patch-operations op=remove,path=/*/*/throttling/rateLimit `
                     op=remove,path=/*/*/throttling/burstLimit

# Devolver la concurrencia a 20
aws lambda put-function-concurrency --function-name bible-references `
  --reserved-concurrent-executions 20 --region mx-central-1
```

Verificado tras aplicar: `/api/bible/chapter/1/1` por CloudFront en **200** (1,03 s) y el
frontend en **200**.

**Ninguno de los dos está en Terraform** —API Gateway no aparece en el módulo y el state sigue
sin adoptarse—, así que este apartado es la única constancia del cambio.

### La regla del WAF ha disparado dos veces

Consultado el 2026-08-25, métrica `BlockedRequests` con dimensiones `WebACL` + `Rule` (**sin**
`Region`; con la dimensión `Region=CloudFront` que citaba este documento la consulta devuelve
vacío):

| Momento (UTC) | Bloqueos |
|---|---|
| 2026-08-15 17:00 | 24 |
| 2026-08-17 18:00 | 150 |

Ambos concentrados en una sola hora. **No están caracterizados**: sin logs de acceso no se
puede saber si fue un rastreador, una prueba propia o tráfico real bloqueado por error. Queda
anotado para no darlo por explicado.

## Cuánto cuesta un arranque en frío (medido el 2026-08-14)

Medido en producción sobre 21 restauraciones y 42 invocaciones en caliente, leyendo las
líneas `RESTORE_REPORT` y `REPORT` de CloudWatch.

| Fase | Mediana | Rango |
|---|---|---|
| Restauración de SnapStart | **615 ms** | 314 – 820 |
| Primera invocación tras restaurar | **2.393 ms** | 1.941 – 3.716 |
| **Total de un arranque en frío** | **~3,0 s** | 2,3 – 4,5 |
| Invocación en caliente | **138 ms** | 19 – 1.275 (p90 706) |

**Lo caro no es restaurar el snapshot, es la primera invocación después**: 2,4 s frente a
0,6 s. Ahí es donde HikariCP reabre conexiones contra PostgreSQL y se ejecuta la primera
consulta de JPA. Optimizar SnapStart más allá de esto daría poco; el margen está en la
reconexión a la base de datos.

Contraste desde un cliente real, **reutilizando conexión** para no medir el TCP+TLS:

| Camino | TTFB |
|---|---|
| Acierto de caché en el borde | **89 ms** |
| Lambda en caliente | **295 ms** |
| Lambda en frío | **~3.100 ms** |

Es decir: **un arranque en frío cuesta unas 35 veces un acierto de caché**, y unas 10 veces
una invocación en caliente. Esto es exactamente lo que la caché en el borde iba a mitigar y
lo que el plan gratuito impide hoy.

> **Cuidado al medir desde el portátil.** La primera petición carga ~400 ms de TCP+TLS y
> ~800 ms de ida y vuelta al PoP asignado (ATL58, Atlanta). Con ese suelo, un acierto de
> caché y una invocación en caliente salen indistinguibles —817 ms frente a 845 ms— y se
> concluiría que la caché no aporta nada. Solo al reutilizar conexión aparece la diferencia
> real. El arranque en frío sí se ve incluso con el suelo puesto, porque son segundos.

## El límite de 6 MB de Lambda tumbó la búsqueda

Encontrado el 2026-08-14 verificando por el dominio real, después de cambiar el origen.
**Es la única regresión funcional que ha traído la migración**, y ECS la tapaba por completo.

```
LAMBDA_RUNTIME Failed to post handler success response. Http response code: 413.
{"errorMessage":"Exceeded maximum allowed payload size (6291556 bytes)","errorType":"RequestEntityTooLarge"}
Exception in thread "main" ... Runtime.ExitError
```

`GET /api/bible/search` no tenía límite: devolvía todos los versículos que coincidieran.

| Búsqueda | Versículos | Tamaño | Resultado |
|---|---|---|---|
| `que`, `de`, `la` | — | >6 MB | **502** |
| `Dios` | 3.786 | 5,13 MB | 200, al 85% del límite |
| `tierra` | — | 3,66 MB | 200 |
| `Señor` | — | 1,75 MB | 200 |

Dos cosas lo hacían peor de lo que aparenta un 502:

- **La ejecución muere entera** (`Runtime.ExitError`): no es que la petición falle y el
  entorno siga sirviendo, es que se pierde. La siguiente petición paga restauración de
  SnapStart. Una búsqueda mala degrada a quien venga detrás.
- **`Dios` es una búsqueda probable en un sitio de Biblia** y estaba a un 15% del límite.
  No hacía falta un usuario raro para romperlo: bastaba con que crecieran los datos.

De fondo, el endpoint incumplía una regla que ya estaba escrita en
`approach-spec-driven-development.md` —*«para operaciones costosas, usar paginación y
límites por defecto en consultas»*—. Lambda no introdujo el defecto: lo hizo visible.

### Cómo se acotó

- Límite **por defecto 500** versículos, **máximo 2.000** (~2,8 MB con los 1,4 KB por
  versículo medidos). Parámetro `limit` opcional en el endpoint.
- **El recorte va en SQL, no en Java**: un CTE acota los versículos y el join con las
  keywords se hace solo sobre ellos. Un `LIMIT` sobre el resultado del join cortaría un
  versículo por la mitad, devolviéndolo con parte de sus keywords.
- Se piden **`limit + 1`** versículos. Si vuelve el de más, es que había más: así se sabe si
  el resultado está truncado sin una segunda consulta de conteo, que repetiría el escaneo
  con la expresión regular sobre toda la Biblia.
- La respuesta gana `returnedVerses` y `truncated`. Son **campos añadidos**, no cambios:
  `verses` conserva su forma y un cliente que solo lea esa lista no necesita tocarse.
  `truncated` existe porque un recorte silencioso es indistinguible de un resultado
  completo, y el usuario concluiría que la Biblia contiene menos apariciones de las que hay.

> **Lo que esto dice del método de verificación.** El fallo no lo encontró ninguna prueba
> —ni podía: la consulta usa `unaccent` y `~*` de PostgreSQL, y las pruebas corren sobre
> H2—. Apareció al ejercitar el endpoint contra producción con datos reales. Merece la pena
> repetir ese barrido, endpoint por endpoint, en vez de dar por buena la ruta feliz.

### Excluir `tomcat-embed:*` con comodín rompe la validación

`tomcat-embed-el` **no es un servidor**: es la implementación de Jakarta EL que Hibernate
Validator usa para interpolar mensajes. El comodín se la llevaba y la aplicación no
arrancaba:

    HV000183: Unable to initialize 'jakarta.el.ExpressionFactory'

El fallo aparece al construir la `SessionFactory` de Hibernate, sin pista alguna de que el
problema sea el empaquetado. Se excluyen `tomcat-embed-core` y `tomcat-embed-websocket` uno
a uno.

> **Por qué las pruebas no lo detectaron.** `StreamLambdaHandlerTest` corre sobre el
> classpath de Maven, no sobre el JAR reempaquetado, así que **ninguna prueba local puede
> detectar un error de exclusión de shade**. Es una limitación estructural: la única forma
> de cubrirlo es una verificación contra el artefacto —comprobar clases clave dentro del
> ZIP— o un despliegue de prueba.

## Versión 17 — `StrongCode` y conteos con proyección (2026-10-20)

| Cambio | Efecto |
|---|---|
| `model/StrongCode` sustituye los `contains(" ")` / `split(" ")` de `StrongService` | `"H430 "` daba **500** en `/stats` (`ArrayIndexOutOfBoundsException`) y **404 falso** en `/details`; ahora 200. Minúsculas y espacios dobles se normalizan; `%`, `_` o más de dos códigos dan 404 sin consultar |
| Proyección `KeywordWordCount` en los cuatro conteos por palabra | Fuera `Object[]` y `((Number) r[1]).intValue()`; fuera el parámetro `reversedStrongCode`, que ningún query usaba |
| `keywordStats` en la rama simple | Si la palabra más frecuente era nula se vaciaba la lista entera; ahora sale como `""`, igual que en los compuestos |

Mismo procedimiento que la 8, con la comprobación del JAR. Antes de mover el alias se invocó la
17 directamente contra `/actuator/health`: `UP`, con `db`. Después se invalidó
`/api/strongs/*` en CloudFront.

**Cómo distinguir versiones desde fuera.** Dos técnicas que sirvieron aquí:

- **Saltarse la caché del CDN con minúsculas.** `h430` es otra ruta y otra clave de CloudFront,
  y no está en `WordCacheCondition`, así que el origen responde `no-store`: llega siempre a la
  Lambda. Un `?query` no sirve, porque la clave no lleva query strings.
- **Marcar con una petición que cambia de estado sin provocar un 5xx.** `/details` con
  `"H430 "` daba 404 en la 16 y 200 en la 17. `/stats` con lo mismo distinguía igual, pero
  en la 16 daba 500 y habría disparado `bible-references-api-5xx`.

> **CloudFront solo admite el comodín al final de la ruta.** `/api/strongs/*/stats` no es una
> ruta de invalidación válida; se invalida `/api/strongs/*`, que cubre lo mismo porque
> `/details` va siempre con `no-store`.

## Vuelta atrás — coste según la fase

La reversibilidad de esta migración no es una propiedad fija: cae de golpe en un punto
concreto. Conviene saber cuál es antes de cruzarlo.

| Fase | Cómo se vuelve | Coste |
|---|---|---|
| A — la función existe, nada la enruta | `aws lambda delete-function` | **Gratis.** Superada el 2026-08-14 |
| B — CloudFront apunta a la API Gateway, el ALB sigue en pie | Devolver el `TargetOriginId` de `/api/*` a `Backend-API` | ~10 min. Superada el **2026-08-15** |
| **C — ahora** (ALB borrado) | Reconstruir balanceador, target group, listener, servicio y task definition | **Horas**, y con pérdidas |

### El ALB se borró el 2026-08-15

**Trece días antes de la fecha que este documento fijaba** (*«no antes del 2026-08-28 y solo
tras verificar»*) y **sin haber adoptado el state de Terraform**, que la sección de más abajo
pone como requisito previo. Fue una decisión consciente tomada tras plantear ambas cosas, no un
descuido: el balanceador costaba **~0,93 USD/día** y no servía tráfico desde el 2026-08-14.

Qué se borró y qué quedó:

| Recurso | Estado |
|---|---|
| `bible-alb` | **Borrado.** Configuración completa en `docs/alb-borrado-configuracion.md` |
| Sus dos direcciones IPv4 (`78.14.156.106`, `78.14.161.153`) | **Liberadas con el balanceador** |
| Target group `bible-tg` | Huérfano, sin balanceador. **No cuesta nada**; se deja |
| `sg-0f48e25801a170bef` (SG del ALB) | Huérfano. Sigue referenciado por el ingress de `bible-ecs-sg` |

> **Detalle que se anticipó mal.** Se dio por hecho que las dos IPv4 eran Elastic IP asignadas
> a la cuenta y que habría que liberarlas aparte, o seguirían facturando como `IdleAddress`.
> **No lo eran**: `describe-addresses` las mostraba porque estaban asociadas a las interfaces
> del balanceador, pero las gestionaba el propio ELB y desaparecieron con él —
> `describe-addresses` devuelve `[]`—. La comprobación posterior costó un comando; darlo por
> bueno sin comprobarlo habría dejado ~11 USD/mes corriendo sin que nadie lo mirara.

Verificado inmediatamente después, con `X-Client-Origin`:

| Caso | Resultado |
|---|---|
| `/api/bible/chapter/1/1`, `/1/40`, `/search?q=Dios`, `/strongs/H430/stats` | **200** en 0,53–1,41 s |
| `/` (la SPA) | 200 |
| `/api/bible/chapter/1/1` **sin** `X-Client-Origin` | **403** — el filtro sigue en su sitio |
| Balanceadores en la región | **0** |
| ENIs restantes en la VPC | 2 de Lambda + 1 de RDS |

**Procedimiento detallado y probado: `docs/runbook-vuelta-atras-lambda.md`.**

Hay además una vuelta atrás más fina y más rápida para problemas **de la aplicación**, no de
la arquitectura: como la API Gateway apunta al **alias `live`** y no a una versión concreta,
basta con mover el alias a la versión anterior. Surte efecto de inmediato, sin esperar
propagación de CloudFront y sin tocar la distribución.

```bash
aws lambda update-alias --function-name bible-references --name live --function-version 17
```

Historial del alias, de más nueva a más vieja (actualizado el 2026-11-22):

| Versión | Qué cambia respecto a la anterior | Existe |
|---|---|---|
| **18 — la que sirve hoy** (2026-11-22) | `/stats` de un código del léxico sin keywords responde 200 con listas vacías, no 404 (H854); `keywordStatsTranslated` limitado a 25 en simples y compuestos | ✅ |
| **17 — destino de vuelta atrás** | `StrongCode`: un código mal formado ya no da 500; conteos por palabra con proyección; `keywordStats` ya no se vacía con cabeza nula | ✅ |
| 16 | `keywordBookStats` en `/stats`; filtro `idBook` en `/details` | ✅ |
| 15 | `APP_FRONTEND_ORIGIN` admite `www.escrituraclave.com`; sin `localhost` en producción | ❌ borrada el 2026-10-20 |
| 14 | `f_unaccent` + índice trigram en `/search`; caché a nivel de servicio; outbox fuera del camino de lectura | ❌ borrada el 2026-10-20 |
| 13 | Rotación de `X-Origin-Verify`; código idéntico a la 12 | ❌ borrada el 2026-10-20 |
| 12 | `diskSpace` mide `/tmp` y `show-components: always`: `/actuator/health` pasa de 503 a 200 | ❌ borrada el 2026-08-23 |
| 11 | `connectTimeout: 2` del driver pgjdbc | ❌ borrada el 2026-08-23 |
| 10 | `connection-timeout: 5000` de Hikari | ❌ borrada |
| 9 | `includeLXX` retirado de `/search` y `/strongs`; caché en `StrongController` | ❌ borrada |
| 8 | `no-store` explícito en `/api/bible/search` | ❌ borrada |
| 7 | Pool de Hikari acotado a 3/1 (solo variables de entorno) | ❌ borrada |
| 6 | Búsqueda acotada al límite de 500/2.000 versículos | ❌ borrada |

### Las versiones 1–10 se borraron el 2026-08-15

Decisión deliberada, tomada tras comprobar que **nada apuntaba a ellas**: solo existe el alias
`live`, sin concurrencia aprovisionada ni *event source mappings*. La operación es
**irreversible** —al borrar una versión desaparece su copia del código— y deja la vuelta atrás
de aplicación con **un solo escalón: la 11**.

Qué queda en el bucket `bible-references-lambda-artifacts-274869222183` tras la limpieza:

| Artefacto | Estado |
|---|---|
| `bible-references-lambda.jar` | El de la **versión 12**, el que sirve hoy |
| `archivo/bible-references-lambda-v11.jar` | El del **único destino de vuelta atrás** |
| `archivo/…-v7.jar`, `-v8.jar`, `-v9.jar` | **Borrados el 2026-08-15**, huérfanos desde que se borraron sus versiones |
| Versiones 1–10 | **En ninguna parte.** No se pueden reconstruir sin recompilar desde el código de entonces |

**El bucket queda con dos objetos y una regla fácil de mantener: lo que sirve y aquello a lo
que se puede volver.** No es una medida de ahorro —a 57,7 MiB por JAR, el bucket entero
rondaba los 0,007 USD/mes— sino de correspondencia: un artefacto archivado cuya versión de
Lambda ya no existe **parece** una vía de vuelta atrás y no lo es, y esa confusión sale cara
justo en el momento en que hace falta volver.

**Decidido: la próxima limpieza, al llegar a la versión 15.** El procedimiento de despliegue
archiva el JAR anterior antes de sobrescribir, así que cada despliegue deja un objeto más y una
versión más; el desajuste reaparece solo. En vez de limpiar en cada despliegue —un paso extra
que se olvida— se agrupa cada tres o cuatro versiones. El criterio de entonces será el mismo de
hoy: **dejar la que sirve y la anterior, con sus JAR, y nada más**, comprobando antes con
`list-aliases`, `list-provisioned-concurrency-configs` y `list-event-source-mappings` que nada
apunte a lo que se va a borrar.

> La alternativa de fondo sería **dejar de archivar en S3**: cada versión publicada de Lambda
> ya guarda su propia copia del código, de modo que el archivo solo hace falta si se piensan
> borrar versiones. Como sí se piensan borrar, se mantiene.

> **Lo que esto cambia en la práctica.** Poco, porque volver más de un escalón nunca fue un
> escenario realista: cada versión de la 6 a la 11 arreglaba algo que la anterior tenía roto
> —el límite de 6 MB, el pool de conexiones, el cuelgue de 30 s— así que retroceder a la 8
> habría reintroducido defectos conocidos. La vuelta atrás útil siempre fue *la anterior*. Lo
> que sí desaparece es la posibilidad de **inspeccionar** una versión vieja para comparar
> comportamientos, que es como se comprobó que el 404 de `/details` no era una regresión
> —invocando la 8 y la 9 por separado—. Esa técnica ya no está disponible hacia atrás.

### Las versiones 13–15 se borraron el 2026-10-20

Al publicar la 17, con el mismo criterio: **dejar la que sirve y la anterior**. Antes de
borrar se comprobó que nada apuntaba a ellas: `live` es el único alias, en la 17 y sin
`RoutingConfig`; sin concurrencia aprovisionada ni *event source mappings*; `bible-keep-warm`
invoca el alias. Quedan `$LATEST`, la 16 y la 17.

> **Corrección a lo dicho arriba sobre el archivo en S3.** El bucket tiene la regla de ciclo de
> vida `expirar-artefactos-antiguos`, que **expira todo a los 30 días**, sin prefijo. El paso
> del procedimiento de despliegue que copia el JAR anterior a `archivo/` produce una copia con
> fecha de caducidad, no un archivo: el 2026-10-19 el bucket estaba vacío. Hoy contiene el JAR
> de la 17 y `archivo/…-v16.jar`, y **ambos expiran el 2026-11-19**. La vuelta atrás real es
> `update-alias`: cada versión publicada conserva su propia copia del código, y esa no caduca.

**Próxima limpieza: al publicar la 19.**

### Qué mantenía barata la fase B *(histórico — la fase B terminó el 2026-08-15)*

Cosas que **no** había que hacer mientras durase el periodo de prueba:

- No parar el servicio ECS ni bajar `desiredCount` de forma permanente.
- No tocar el ALB, su listener ni la regla con `X-Origin-Verify`.
- Que el pipeline siguiera desplegando a ECS, para que la task no quedase con una imagen vieja.

> Ninguna de las tres se cumplió hasta el final, y conviene que quede escrito en vez de
> borrarse: el servicio ECS llevaba desde el 2026-08-14 en `desiredCount 0` sin volver a
> arrancar, y el ALB se borró el 2026-08-15. La fase B duró **un día**, no las dos semanas
> previstas.

> **Ojo con el apagado nocturno.** Entre las 23:00 y las 07:55 el servicio ECS está a 0
> tasks. Una vuelta atrás en esa franja exige subirlo primero (`update-service
> --desired-count 1`) y esperar el arranque, ~1 minuto más. No es un problema, pero sí un
> paso extra que conviene tener presente a las tres de la mañana.

### Por qué la fase C es cara, y cómo abaratarla

Borrar el ALB es la puerta de un solo sentido. Recrearlo da un **nombre DNS nuevo**, así que
además hay que rehacer la regla del listener con el header secreto —que vive únicamente en
la configuración de CloudFront— y volver a apuntar el origen. Súmese el target group con su
health check, las reglas de los grupos de seguridad, el servicio y la task definition.

**El módulo de `terraform/` ya declara todo eso.** Lo que falta es que sirva de algo:

    $ ls terraform/*.tfstate*
    (no existe)

Sin state adoptado, Terraform no puede reconstruir nada; el módulo es documentación
ejecutable, no una vía de recuperación. **Adoptar el state (hallazgo 8) antes de dar de baja
el ALB convierte "horas de reconstrucción manual" en un `terraform apply`.** Visto así, el
hallazgo 8 deja de ser higiene y pasa a ser el seguro de esta migración.

### La tensión que no tiene solución elegante

Mantener ECS vivo en paralelo cuesta **~31 USD/mes** —el ALB, sus IPv4 y Fargate—, que es
justo lo que la migración pretende ahorrar. Es decir: **el ahorro y la red de seguridad son
mutuamente excluyentes.** Durante el periodo de prueba se paga el doble y no se ahorra nada.

Lo razonable es fijar de antemano cuánto dura ese periodo y qué hay que ver para cerrarlo
—por ejemplo dos semanas sin errores 5xx y con latencias dentro de lo medido— en vez de
dejarlo abierto y acabar pagando ambas arquitecturas durante meses.

### Cambios ya aplicados y cómo revertirlos

Ninguno depende de Lambda; todos son independientes de esta migración:

| Cambio | Reversión |
|---|---|
| Quitar el 403/404 → 200 con HTML | Volver a añadir las dos `CustomErrorResponses`; la función `biblia-spa-router` puede quedarse o quitarse |
| RDS sin IP pública, SG dedicado | `runbook-ahorro-costos.md`, punto 1 |
| Fargate Spot | `update-service --capacity-provider-strategy capacityProvider=FARGATE,...` |
| Hazelcast → Caffeine | Revertir el commit; no tiene nada que ver con Lambda |

## Estado de las tareas

| # | Tarea | Estado |
|---|---|---|
| — | Hazelcast → Caffeine | ✅ desplegado (revisión 22) |
| 7 | Adaptar la aplicación para Lambda | ✅ código listo |
| 11 | Probar el handler con petición real | ✅ 5 pruebas de extremo a extremo |
| 8 | SnapStart + pool de conexiones | ✅ lo resuelve Spring con `org.crac` |
| 13 | Quitar el 403/404 → 200 con HTML | ✅ **aplicado en CloudFront** |
| 12 | Crear la función Lambda | ✅ **versión 17 activa tras el alias `live`** (desde el 2026-10-20). Vuelta atrás: la 16. Quedan `$LATEST`, la 16 y la 17; la 11 y la 12 se borraron el 2026-08-23 y de la 13 a la 15 el 2026-10-20 |
| 9 | Credenciales a variables de entorno | ✅ aplicado al crear la función |
| — | Comprobar `X-Origin-Verify` en la aplicación | ✅ `OriginVerifyFilter`, verificado en producción |
| 10 | CloudFront → API Gateway | ✅ **aplicado el 2026-08-14** |
| — | Acotar la búsqueda (límite de 6 MB) | ✅ límite 500/2.000 con aviso de truncado |
| — | Caché en el borde | ❌ **bloqueada por el plan gratuito**; la política propia está lista |
| — | Subir la cuota de concurrencia de Lambda (10 → 1.000) | ✅ **concedida el 2026-08-14** |
| — | Acotar el pool de Hikari en la función (3/1) | ✅ **versión 7 activa**; sin esto el techo pasaba a ser `max_connections` de RDS y arrastraba también a ECS |
| — | Reservar concurrencia a la función | ✅ **12 reservadas** (bajada de 20 el 2026-08-25); tope real de la función. RDS: `12 × 3 + 10 = 46` conexiones de ~109 útiles |
| — | Activar los logs de acceso de CloudFront | ❌ **bloqueada por el plan gratuito**, por las dos vías; bucket y grupo de logs ya creados |
| — | Salir del plan gratuito de CloudFront | ⏸️ **desbloquea las dos anteriores de una vez** |
| — | `no-store` explícito en `/search` | ✅ **desplegado en la versión 8**, verificado en producción |
| — | Cabeceras de caché en `StrongController` | ✅ **desplegado en la versión 9**, verificado en producción |
| — | `/api/strongs/{code}/details` devuelve 404 para todo | ⏸️ **preexistente** (también en la 8), no es regresión |
| — | Ping de mantenimiento cada 5 min | ✅ **activo y verificado en caliente el 2026-08-15**; estuvo 12 h sin dispararse por haberse creado fuera de su ventana |
| — | Ampliar la ventana del ping a 08:00–22:55 | ⏸️ hoy quedan **5 h 30 min** con RDS arriba y sin entorno caliente; el coste extra cabe en el *free tier* |
| — | `bible-ecs-start` está DESACTIVADO | ⏸️ **ECS se para cada noche y no vuelve**; el runbook dice lo contrario |
| — | La API no funciona de 23:10 a 07:40 (RDS parada) | ⏸️ decisión de coste ya tomada, pero no estaba escrita |
| — | `/actuator/health` da 503 en Lambda y 200 en ECS | ✅ **era `diskSpace` midiendo `/var/task`**; corregido a `/tmp` en la **versión 12**, confirmado invocándola directamente |
| — | Regla *rate-based* en el WAF | ✅ **`RateLimitPorIP`, 150/IP cada 5 min (30/min), Block** |
| — | Throttling de stage en API Gateway | ✅ **aplicado el 2026-08-25**: 10 rps, ráfaga 20, alcance `*/*`. Antes `methodSettings: {}` |
| — | Web ACL regional sobre el stage de API Gateway | ✅ **aplicado el 2026-08-25**: `bible-references-api-acl`, 500/IP cada 5 min, Block. Única capa con coste (~6 USD/mes). Detalle en `docs/rate-limiting-lambda.md` |
| — | Caracterizar los 150 bloqueos del 2026-08-17 | ⏸️ sin logs de acceso no se puede saber quién fue |
| — | Acotar la regla de tasa a `/api/*` | ❌ **bloqueada**: el *scope-down* exige plan **PRO** |
| — | Ventana de evaluación distinta de 5 min | ❌ **`DISALLOWED`**: no se compra, no existe |
| — | La API se cuelga (504) en la parada nocturna | ✅ **versión 11**: 500 en 4 s en vez de 504 en 30 s |
| — | Verificar el camino feliz de la versión 11 | ✅ **comprobado el 2026-08-15 con RDS arriba**, en el barrido de la versión 12 (que la incluye) |
| 14 | Rutas inexistentes devuelven 500, deberían dar 404 | ⏸️ |
| 15 | Rotar `X-Origin-Verify` **y la contraseña de RDS** | 🔵 PARCIAL — el header **rotado el 2026-08-23** (versión 13, alias `live` movido; origen `Backend-API` borrado con su copia del valor viejo). La contraseña de RDS **sigue sin rotar** |
| — | Adoptar el state de Terraform (hallazgo 8) | ❗ **quedó sin hacer y ya no es preventivo**: el ALB se borró antes, así que hoy es la vía de reconstrucción, no el seguro |
| — | Dar de baja el ALB y sus IPv4 | ✅ **borrado el 2026-08-15**; ~28,3 USD/mes menos. Config en `docs/alb-borrado-configuracion.md` |
| — | Actualizar `docs/aws-architecture.md` y los runbooks del ALB | ⏸️ **describen recursos que ya no existen** |

**Nada de la aplicación está commiteado todavía.** La función Lambda corre con un artefacto
construido desde el árbol de trabajo local, no desde `main`.

> Con la versión 8 esto deja de ser una molestia y pasa a ser el mayor riesgo abierto: lo que
> sirve producción **existe en un solo sitio, el portátil**. Un `git clean` mal dado, o
> cualquier accidente en esa carpeta, y no hay forma de reconstruir el artefacto desplegado.
> El JAR sigue en S3 y cada versión de Lambda guarda su copia, así que la función seguiría
> funcionando —pero el **código fuente** que la produjo no estaría en ninguna parte.
>
> Commitear es, ahora mismo, más urgente que cualquier tarea pendiente de esta lista.








