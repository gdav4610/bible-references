# Rate limiting en la arquitectura Lambda

Estado a **2026-08-25**. Recoge las cuatro capas de limitación de tasa que hoy protegen la API,
por qué existe cada una, qué restricciones del plan condicionaron su forma, y cómo verificarlas
o revertirlas.

Complementa a `docs/migracion-lambda.md`, que narra la migración; este documento se ocupa solo
de la limitación de tasa y pretende ser autosuficiente.

## Resumen: qué hay y dónde

```
Cliente
  │
  ├──► CloudFront  E1MTT1XP2UUMYU  (escrituraclave.com)
  │      │   [1] WAF CreatedByCloudFront-228216dd
  │      │       RateLimitPorIP: 150 / IP / 5 min  → Block
  │      │   inyecta la cabecera X-Origin-Verify
  │      ▼
  │    API Gateway  m9zcdngc1m  stage prod  (REST API v1, REGIONAL)
  │      │   [2] Throttling de stage: 10 rps, ráfaga 20  → 429
  │      │   [3] WAF bible-references-api-acl
  │      │       RateLimitPorIP: 500 / IP / 5 min  → Block
  │      ▼
  │    Lambda bible-references  (alias live)
  │          [4] Concurrencia reservada: 12
  │          OriginVerifyFilter: 403 si falta X-Origin-Verify
  │
  └──► m9zcdngc1m.execute-api.mx-central-1.amazonaws.com  ← ruta directa, pública
         entra por [2], [3] y [4]; NO pasa por [1]
```

| # | Capa | Alcance | Límite | Respuesta al superarlo |
|---|---|---|---|---|
| 1 | WAF en CloudFront | por IP del cliente | 150 / 5 min | 403 del WAF |
| 2 | Throttling de stage | **global**, todo el stage | 10 rps, ráfaga 20 | 429 de API Gateway |
| 3 | WAF regional en el stage | por IP de origen | 500 / 5 min | 403 del WAF |
| 4 | Concurrencia reservada | global, la función | 12 simultáneas | 500 vía `Throttles` |

## Por qué hacían falta las capas 2 y 3

Hasta el 2026-08-25 **la única limitación de tasa era la capa 1**, y cuelga de CloudFront. El
backend, sin embargo, cuelga de API Gateway, cuya URL de ejecución es pública y aparece tanto
en la configuración de CloudFront como en los logs.

Comprobado con una petición directa:

```
$ curl -D- https://m9zcdngc1m.execute-api.mx-central-1.amazonaws.com/prod/api/bible/chapter/1/1

HTTP/1.1 403 Forbidden
Content-Type: text/plain; charset=UTF-8
Content-Length: 9        ->  "Forbidden"
```

Ese cuerpo es literalmente el de `OriginVerifyFilter` (`OriginVerifyFilter.java:97-98`), no el
de API Gateway. **El 403 lo emitió la Lambda ya invocada.** Las métricas lo confirmaron: el
baseline es 1 invocación cada 5 min por el ping de mantenimiento, y el bucket de las 22:45 UTC
subió a 4 tras las pruebas.

> **La conclusión que importa: una petición rechazada costaba lo mismo que una servida.** El
> `OriginVerifyFilter` protege la *autorización* —quién puede usar la API— pero no la *tasa*.
> Un atacante que conociera la URL podía invocar la Lambda al ritmo que quisiera, agotar las
> concurrencias reservadas y dejar sin servicio a los usuarios que sí entran por el CDN, sin
> que la regla de 150/IP del borde viera nada.

### Corrección: es una REST API, no una HTTP API

`docs/migracion-lambda.md` y el javadoc de `OriginVerifyFilter` dan por hecho que se trata de
una **HTTP API**. No lo es:

```
$ aws apigatewayv2 get-apis --region mx-central-1      # HTTP APIs (v2)
[]
$ aws apigateway get-rest-apis --region mx-central-1    # REST APIs (v1)
[ { "Id": "m9zcdngc1m", "Name": "bible-references-api", ... } ]
```

Importa porque cambia lo que se puede hacer: una **REST API sí admite throttling de stage y
asociar un Web ACL**. Sobre esa base se construyeron las capas 2 y 3.

## Capa 2 — Throttling de stage (10 rps, ráfaga 20)

Estado previo: `methodSettings: {}`, es decir **sin throttling propio**; heredaba el límite de
cuenta de 2.500 rps / ráfaga 1.250, que es un techo administrativo, no una defensa.

```powershell
aws apigateway update-stage --rest-api-id m9zcdngc1m --stage-name prod --region mx-central-1 `
  --patch-operations 'op=replace,path=/*/*/throttling/rateLimit,value=10' `
                     'op=replace,path=/*/*/throttling/burstLimit,value=20'
```

**Por qué 10/20.** El tráfico legítimo de API son ~9 peticiones/día. El pico realista —un
lector abre un capítulo y pincha diez palabras, cada código Strong es otra llamada— son ~11
peticiones en un minuto, 0,2 rps. El límite queda dos órdenes de magnitud por encima del uso
real y 250 veces por debajo del techo que se heredaba.

**Lo que de verdad aporta es cambiar el modo de fallo.** Con 12 concurrentes y ~300 ms por
invocación la función satura alrededor de 40 rps —8 rps si todo fuera `/search`, que tarda
1,1–1,6 s—. Al poner el techo en 10:

| | Antes | Ahora |
|---|---|---|
| Quién rechaza | Lambda, emitiendo `Throttles` | API Gateway |
| Qué ve el cliente | 500 | **429**, semánticamente correcto |
| Qué cuesta | una invocación facturada | **nada** |

> **Limitación: el throttling de stage es global, no por IP.** No distingue a un abusador de la
> suma de usuarios legítimos: quien machaque la URL directa consume el mismo presupuesto de 10
> rps que el tráfico del CDN. Contiene el gasto y protege RDS, pero por sí solo no impide que
> alguien degrade el servicio a los demás. De ahí la capa 3.

## Capa 3 — Web ACL regional sobre el stage

```
Nombre    : bible-references-api-acl
Id        : 0214d03b-9b9e-4c1e-8072-575b95d3cb48
ARN       : arn:aws:wafv2:mx-central-1:274869222183:regional/webacl/
            bible-references-api-acl/0214d03b-9b9e-4c1e-8072-575b95d3cb48
Scope     : REGIONAL          Capacidad: 2 WCU
Default   : Allow
Regla [0] : RateLimitPorIP  Limit=500  EvaluationWindowSec=300  AggregateKeyType=IP  → Block
Asociado a: arn:aws:apigateway:mx-central-1::/restapis/m9zcdngc1m/stages/prod
```

Contiene **solo** la regla de tasa. No se añadieron grupos gestionados (`CommonRuleSet` y
compañía) porque el tráfico ya pasa por los tres del borde en el camino normal, y cada grupo
suma 1 USD/mes. Si algún día preocupa el camino directo, ahí es donde se añadirían.

### El detalle de diseño que fija el umbral: qué IP ve esta capa

En el stage regional, **el tráfico legítimo llega con la IP del edge de CloudFront**, no con la
del usuario final. La agregación `AggregateKeyType=IP` mete por tanto a todos los usuarios del
CDN en unos pocos contadores compartidos. El tráfico directo, en cambio, sí se contabiliza con
la IP real del que llama — que es exactamente lo que esta capa quiere frenar.

Se valoró `FORWARDED_IP` sobre `X-Forwarded-For`, que resolvería la agregación del CDN, y **se
descartó**: quien ataca directamente controla esa cabecera y podría rotarla para esquivar la
regla por completo. Se prefiere una capa que no se pueda evadir aunque agregue de más.

**Consecuencia práctica:** el umbral no puede apretarse tanto como sugeriría el hecho de que
aquí no hay estáticos inflando el contador, porque el contador del CDN es compartido. Con 500
cada 5 minutos el margen sigue siendo enorme —la API recibe ~9 peticiones al día en total, así
que ni multiplicando el tráfico por mil se acercaría un solo edge a ese número— mientras que un
abusador directo queda limitado a ~1,7 rps y necesitaría **6 IPs distintas** para agotar por sí
solo el presupuesto de 10 rps de la capa 2. Antes le bastaba con una.

> **Este es el número a revisar si el tráfico crece.** Es el único umbral del sistema cuyo
> margen depende de cuánta gente comparte edge, no de cuánto pide cada persona.

## Capa 4 — Concurrencia reservada (12)

Bajada de 20 a 12 el 2026-08-25. Es el último tope: aunque todo lo demás fallara, la función no
puede ejecutar más de 12 invocaciones a la vez.

Efecto sobre RDS, con el pool de Hikari en 3 por entorno:

| Concurrencia | Conexiones a RDS | De ~109 útiles |
|---|---|---|
| 20 (antes) | `20 × 3 + 10 = 70` | 64 % |
| **12 (ahora)** | `12 × 3 + 10 = 46` | **42 %** |

## Restricciones del plan encontradas

Dos intentos rechazados por AWS, ambos relevantes para entender por qué las reglas tienen la
forma que tienen:

| Intento | Respuesta de AWS |
|---|---|
| Acotar la regla del borde a `/api/*` con un *scope-down* | `String match statement` requiere plan **PRO** |
| Ventana de evaluación de 60 s en el ACL regional | `WAFInvalidOperationException: Your request contains fields that belong to a feature you are not allowed to use` |

**La ventana de 5 minutos es obligatoria en ambas capas.** El equivalente a un objetivo por
minuto es `Limit = objetivo × 5`, con una diferencia real de comportamiento: **la ventana de 5
minutos tolera ráfagas**. Un cliente puede gastar las 500 en diez segundos y quedar bloqueado el
resto del periodo, en vez de repartirlas a 100 por minuto. Para frenar abuso sostenido da igual;
para ráfagas cortas es más permisivo de lo que sugiere la cifra.

> Nótese que el error de la capa 3 es genérico, no el `RequiredPricingPlan: DISALLOWED` que
> devolvió la misma operación en el borde. La restricción es la misma; el mensaje, peor.

## Coste

| Concepto | Coste |
|---|---|
| WAF del borde `CreatedByCloudFront-228216dd` | **0** — incluido con CloudFront, no aparece en la factura |
| Throttling de stage | **0** — es configuración, no un recurso |
| **WAF regional `bible-references-api-acl`** | **~6 USD/mes** — 5 por Web ACL + 1 por regla, + 0,60 por millón de peticiones |
| Concurrencia reservada | **0** — no se factura reservar, solo ejecutar |

La capa 3 es la única con coste recurrente, y es tarifa estándar de AWS WAF: a diferencia del
ACL del borde, un Web ACL regional propio **sí se factura**. Conviene confirmarlo en el próximo
ciclo:

```powershell
aws ce get-cost-and-usage --time-period Start=2026-09-01,End=2026-10-01 --granularity MONTHLY `
  --metrics UnblendedCost --group-by Type=DIMENSION,Key=SERVICE --region us-east-1
```

## Cómo verificar el estado

```powershell
# Capa 1 - regla en el borde y que el ACL siga asociado a la distribución
aws wafv2 get-web-acl --name CreatedByCloudFront-228216dd --scope CLOUDFRONT `
  --id 49dcae95-4901-4004-92b8-640178961cef --region us-east-1
aws cloudfront list-distributions --query "DistributionList.Items[].{Id:Id,WebACL:WebACLId}"

# Capa 2 - throttling del stage
aws apigateway get-stage --rest-api-id m9zcdngc1m --stage-name prod --region mx-central-1 `
  --query 'methodSettings."*/*".{Rate:throttlingRateLimit,Burst:throttlingBurstLimit}'

# Capa 3 - ACL asociado al stage
aws wafv2 get-web-acl-for-resource `
  --resource-arn "arn:aws:apigateway:mx-central-1::/restapis/m9zcdngc1m/stages/prod" `
  --region mx-central-1

# Capa 4 - concurrencia
aws lambda get-function-concurrency --function-name bible-references --region mx-central-1
```

### Bloqueos: qué dimensiones usar

**La métrica se emite con `WebACL` + `Rule`, sin `Region`.** Consultarla con la dimensión
`Name=Region,Value=CloudFront` que citaba `migracion-lambda.md` devuelve un resultado vacío que
parece «no ha bloqueado nada» y en realidad es «esa serie no existe».

```powershell
# Capa 1
aws cloudwatch get-metric-statistics --namespace AWS/WAFV2 --metric-name BlockedRequests `
  --dimensions Name=WebACL,Value=CreatedByCloudFront-228216dd Name=Rule,Value=RateLimitPorIP `
  --start-time 2026-07-26T00:00:00Z --end-time 2026-08-25T23:00:00Z `
  --period 86400 --statistics Sum --region us-east-1

# Capa 3
aws cloudwatch get-metric-statistics --namespace AWS/WAFV2 --metric-name BlockedRequests `
  --dimensions Name=WebACL,Value=bible-references-api-acl Name=Rule,Value=RateLimitPorIP `
  --start-time 2026-08-25T00:00:00Z --end-time 2026-09-25T00:00:00Z `
  --period 86400 --statistics Sum --region mx-central-1
```

En caso de duda, `aws cloudwatch list-metrics --namespace AWS/WAFV2 --region <region>` enumera
las series que existen de verdad.

### Bloqueos observados hasta la fecha

| Momento (UTC) | Capa | Bloqueos |
|---|---|---|
| 2026-08-15 17:00 | 1 (borde) | 24 |
| 2026-08-17 18:00 | 1 (borde) | 150 |

Ambos concentrados en una sola hora. **No están caracterizados**: sin logs de acceso —bloqueados
por el plan gratuito de CloudFront— no se puede saber si fue un rastreador, una prueba propia o
tráfico legítimo bloqueado por error. Queda anotado para no darlo por explicado.

> **Un bloqueo indebido sería invisible.** Es la razón de fondo para dejar los umbrales holgados
> mientras no haya logs, y para mirar `BlockedRequests` antes de suponer que quien disparó una
> regla era un atacante.

### Barridos de escáner, detectados el 2026-08-25

Al medir el volumen de `Count` para calibrar la alarma aparecieron tres horas muy por encima de
la línea base, que son **2–14 peticiones al día**:

| Hora (UTC) | `Count` | `4XXError` | Invocaciones Lambda |
|---|---|---|---|
| 2026-08-24 10:00 | 178 | 178 | 178 |
| 2026-08-24 22:00 | 179 | 179 | 191 |
| 2026-08-25 16:00 | 180 | 180 | 192 |

**El 100 % fueron 4XX, y la Lambda se invocó en todas.** Los logs identifican el tráfico sin
ambigüedad — rutas de frameworks ajenos a esta aplicación:

```
/api/wanlshop/common/init   /api/currency/quotation_new   /api/dict/getServiceConfig
/api/common/getAllSetting   /api/public/recent-loans      /api/Config/getShowConfig
/api/banner                 /api/baseconfig               /api/common/getBaseConfig
```

Es un escáner automatizado buscando endpoints conocidos. Dos deducciones:

1. **Entró por CloudFront, no por la ruta directa.** El filtro que lo rechaza es
   `OriginValidationFilter` por falta de `Origin`; si hubiera venido por la URL de ejecución lo
   habría cortado antes `OriginVerifyFilter`, que corre con `@Order(0)`. Luego el tráfico traía
   la cabecera `X-Origin-Verify` que inyecta el CDN.
2. **La regla del borde no lo paró.** El barrido del 2026-08-25 se concentró en **dos minutos**
   —99 peticiones a las 16:24 y 80 a las 16:25— y `BlockedRequests` **no registró nada** esos
   días. 179 en dos minutos deberían haber superado el límite de 150/5 min si vinieran de una
   sola IP.

Caben dos explicaciones y **no se pueden distinguir sin logs de acceso**: o el escáner rotó
IPs, o la regla reaccionó tarde —las reglas *rate-based* de WAF actualizan contadores cada
~30 s, y una ráfaga corta puede colarse casi entera antes de que empiece a bloquear—.

> **Lo que esto enseña sobre el diseño.** Las reglas *rate-based* con ventana de 5 minutos son
> buenas frenando abuso **sostenido** y malas frenando **ráfagas cortas**. El coste real de
> estos barridos no fue el bloqueo que no ocurrió, sino las ~550 invocaciones de Lambda que
> costaron lo mismo que peticiones servidas. Es el argumento más concreto a favor de las capas
> 2 y 3, que rechazan sin invocar.

## Rutas explícitas en API Gateway (2026-08-25)

La medida que sí frena los barridos descritos arriba, y la única que los frena **por completo**.

### El problema

El stage estaba configurado con un único recurso comodín `/{proxy+}`: *todo* lo que empezara por
`/api/` se reenviaba a la Lambda sin comprobar si la ruta existía. Por eso `/api/banner` y las
demás rutas inventadas del escáner llegaban a ejecutarse y se facturaban, aunque la aplicación
las rechazara.

Ninguna capa de rate limiting resuelve esto: el barrido fue de 34 segundos a 5,3 rps de media,
por debajo de todos los umbrales. Ver el análisis en *Barridos de escáner*.

### El árbol actual

```
/api/bible/chapter/{idBook}/{chapter}   ANY → AWS_PROXY
/api/bible/search                       ANY → AWS_PROXY
/api/strongs/{strongCode}/stats         ANY → AWS_PROXY
/api/strongs/{strongCode}/details       ANY → AWS_PROXY
```

Cuatro rutas, una por endpoint de `BibleController` y `StrongController`. **`/{proxy+}` ya no
existe**, y tampoco el método `ANY` del recurso raíz `/`, que permitía invocar la función desde
`.../prod/` por la ruta directa.

Los recursos intermedios (`/api`, `/api/bible`, `/api/strongs`…) existen sin método: son solo
nodos del árbol.

### El resultado, medido

| Petición | Content-Type | Cuerpo | Quién responde |
|---|---|---|---|
| `/api/banner` | `application/json` | `{"message":"Missing Authentication Token"}` | **API Gateway** |
| ruta real sin `X-Origin-Verify` | `text/plain` | `Forbidden` | `OriginVerifyFilter`, ya dentro de la Lambda |

`Missing Authentication Token` lo genera API Gateway cuando no encuentra la ruta; **ningún
backend puede producirlo**. El contraste entre ambos cuerpos es la prueba de que la Lambda ya no
se invoca. Es también la forma de verificar este cambio en el futuro sin conocer el secreto.

> ⚠️ **Cada endpoint nuevo del código hay que declararlo aquí.** Si se añade un `@GetMapping` y
> no se crea el recurso correspondiente en API Gateway, dará **403 en producción aunque el
> código sea correcto** — y el mensaje `Missing Authentication Token` no sugiere en absoluto
> «falta una ruta». Es el precio de haber cambiado el comodín por una lista explícita.

Se aplicó en dos despliegues, con verificación entre medias: primero se añadieron las rutas
**conservando el comodín** —cambio sin efecto, porque las rutas explícitas tienen precedencia
pero apuntan a la misma función— y solo tras comprobar los cuatro endpoints se borró el comodín
y se volvió a desplegar. Deployments `8k0la0` y `4p6eyj`.

### Reversión

```powershell
$root = "r0j8sesevc"
$proxy = (aws apigateway create-resource --rest-api-id m9zcdngc1m --region mx-central-1 `
  --parent-id $root --path-part "{proxy+}" --query id --output text)
aws apigateway put-method --rest-api-id m9zcdngc1m --region mx-central-1 `
  --resource-id $proxy --http-method ANY --authorization-type NONE --no-api-key-required
aws apigateway put-integration --rest-api-id m9zcdngc1m --region mx-central-1 `
  --resource-id $proxy --http-method ANY --type AWS_PROXY --integration-http-method POST `
  --uri "arn:aws:apigateway:mx-central-1:lambda:path/2015-03-31/functions/arn:aws:lambda:mx-central-1:274869222183:function:bible-references:live/invocations" `
  --timeout-in-millis 29000 --passthrough-behavior WHEN_NO_MATCH
aws apigateway create-deployment --rest-api-id m9zcdngc1m --region mx-central-1 --stage-name prod
```

El permiso de invocación de la Lambda es `m9zcdngc1m/*/*/*`, así que cubre cualquier ruta y no
hay que tocarlo ni al añadir ni al quitar.

### Lo que no resuelve

Un escáner que pida **rutas reales** —`/api/bible/search?q=...` repetido— sigue invocando la
función, y ahí sí actúan las capas de tasa. Este cambio elimina el coste del tráfico dirigido a
rutas inexistentes, que es el 100 % de los barridos observados hasta hoy, no el abuso de los
endpoints legítimos.

## Detección: la alarma sobre `Count`

Antes del 2026-08-25 **no había ninguna alarma** en `mx-central-1`, y la única de la cuenta era
`Alarma` sobre `EstimatedCharges` en `us-east-1` (umbral 12 USD). Es decir: el mecanismo de
detección de un abuso era *que llegara el aviso de facturación*, cuando el dinero ya se gastó.

```
Nombre    : bible-references-api-count-alto
Región    : mx-central-1
Métrica   : AWS/ApiGateway Count, ApiName=bible-references-api, Stage=prod
Condición : Sum > 150 en 300 s, 1 periodo de evaluación
Missing   : notBreaching
Acción    : arn:aws:sns:mx-central-1:274869222183:bible-references-alarms
```

**Por qué 150 en 5 minutos.** Es el mismo número que la regla del borde, de modo que la alarma
salta más o menos cuando una IP empezaría a ser bloqueada allí. Contra la línea base de 2–14
peticiones/día el margen es de tres órdenes de magnitud, y el barrido del 2026-08-25 —179 en dos
minutos— **la habría disparado**.

`notBreaching` es deliberado: el tráfico es tan escaso que hay periodos de cinco minutos sin
ninguna petición, y sin esa opción la alarma viviría en `INSUFFICIENT_DATA`.

### El topic hubo que crearlo

Una alarma solo puede notificar a un topic SNS **de su misma región**, y en `mx-central-1` no
había ninguno; el `ALARM` que usa la alarma de facturación vive en `us-east-1` y no sirve. Se
creó `bible-references-alarms` replicando su suscripción (`gdav4610@gmail.com`, protocolo email).

> ⚠️ **La suscripción queda en `PendingConfirmation` hasta que se pulse el enlace** del correo
> que envía AWS. Mientras tanto la alarma cambia de estado pero **no avisa a nadie**. Comprobar:
>
> ```powershell
> aws sns list-subscriptions-by-topic `
>   --topic-arn arn:aws:sns:mx-central-1:274869222183:bible-references-alarms `
>   --region mx-central-1 --query "Subscriptions[].{Proto:Protocol,End:Endpoint,Arn:SubscriptionArn}"
> ```
>
> Si `Arn` sigue siendo el literal `PendingConfirmation`, no está confirmada.

### Qué no cubre

Vigila el **volumen total del stage**, no la distribución por IP ni la ruta de entrada. No
distingue un barrido de un pico legítimo, y no ve nada de lo que el WAF del borde bloquee antes
de llegar a API Gateway. Para eso haría falta alarmar además sobre `BlockedRequests` de las
capas 1 y 3, que hoy no está hecho.

## Cómo revertir

Estado previo verificado antes de tocar nada el 2026-08-25: stage con `methodSettings: {}`,
`ReservedConcurrentExecutions: 20`, y ningún Web ACL regional en `mx-central-1`.

```powershell
# Capa 3 - desasociar y borrar el ACL regional (requiere el LockToken vigente)
aws wafv2 disassociate-web-acl `
  --resource-arn "arn:aws:apigateway:mx-central-1::/restapis/m9zcdngc1m/stages/prod" `
  --region mx-central-1
$lock = (aws wafv2 get-web-acl --name bible-references-api-acl --scope REGIONAL `
  --id 0214d03b-9b9e-4c1e-8072-575b95d3cb48 --region mx-central-1 `
  --query LockToken --output text)
aws wafv2 delete-web-acl --name bible-references-api-acl --scope REGIONAL `
  --id 0214d03b-9b9e-4c1e-8072-575b95d3cb48 --lock-token $lock --region mx-central-1

# Capa 2 - quitar el throttling; vuelve a heredar el límite de cuenta
aws apigateway update-stage --rest-api-id m9zcdngc1m --stage-name prod --region mx-central-1 `
  --patch-operations op=remove,path=/*/*/throttling/rateLimit `
                     op=remove,path=/*/*/throttling/burstLimit

# Capa 4 - devolver la concurrencia a 20
aws lambda put-function-concurrency --function-name bible-references `
  --reserved-concurrent-executions 20 --region mx-central-1
```

Para **desactivar la capa 3 sin borrarla** —y sin dejar de pagarla— basta con cambiar la acción
de la regla de `Block` a `Count`: sigue emitiendo métrica y deja pasar todo. Es el paso
intermedio razonable si se sospecha de un falso positivo.

Verificado tras aplicar los tres cambios: `/api/bible/chapter/1/1` por CloudFront en **200**
(1,15 s), el frontend en **200**, y la ruta directa a API Gateway sigue en **403** del filtro.

## Nada de esto está en Terraform

Ni el Web ACL regional, ni el throttling del stage, ni la concurrencia reservada, ni la propia
API Gateway. El módulo de `terraform/` no declara API Gateway en absoluto, y el WAF del borde
solo se referencia por ARN desde `variables.tf:45` para no perder la asociación. Además **el
state sigue sin adoptarse**.

Es decir: **este documento es la única constancia de estos cambios**, y el procedimiento de
reversión de arriba, la única forma de deshacerlos de manera fiable. Mientras el state siga sin
adoptarse, cualquier cambio que se haga a mano sobre estas capas debe anotarse aquí.

## Pendiente

| Tarea | Estado |
|---|---|
| **Confirmar la suscripción de correo del topic SNS** | 🔵 **pendiente de pulsar el enlace**; hasta entonces la alarma no avisa |
| Caracterizar los 150 bloqueos del 2026-08-17 | ⏸️ imposible sin logs de acceso |
| Alarmar sobre `BlockedRequests` de las capas 1 y 3 | ⏸️ la alarma actual solo mira volumen total del stage |
| Decidir si los barridos de escáner requieren acción | ⏸️ hoy cuestan ~180 invocaciones por ráfaga; las capas 2 y 3 no los frenan porque entran por el CDN |
| Logs de acceso de CloudFront | ❌ bloqueados por el plan gratuito, por las dos vías |
| Acotar la regla del borde a `/api/*` | ❌ el *scope-down* exige plan **PRO** |
| Ventana de evaluación menor de 5 min | ❌ no permitida por el plan, en ninguna de las dos capas |
| Revisar el umbral de 500 si crece el tráfico | ⏸️ depende de cuánta gente comparte edge |
| Llevar estas capas a Terraform | ⏸️ bloqueado por la adopción del state |
| Confirmar el coste real del ACL regional en la factura | ⏸️ próximo ciclo de facturación |
