# Requisitos no funcionales — bible-references

Instancia de `docs/templates/requirements.md` rellenada con el estado **real** del sistema a
**2026-09-01**, tras publicar la versión 14 de la Lambda.

## Procedencia de cada dato

El valor de este documento depende de que no se confunda lo medido con lo deseado. Cada celda
lleva una marca:

| Marca | Significado |
|---|---|
| **✔** | Verificado hoy contra la API de AWS o contra el código del repositorio |
| **◐** | Medido hoy, **una sola muestra** desde el portátil a través de CloudFront: sirve para el orden de magnitud, no como percentil |
| **✖** | Sin dato, y en la celda se explica por qué |
| **▷** | Propuesta mía, **no acordada**: hay que decidirla antes de darla por buena |

> **Segunda revisión, 2026-09-01.** La primera versión daba por perdida la latencia entera; la
> segunda la midió pero mezclando ventanas de tiempo. Esta fija una sola ventana para todo el
> documento y corrige lo que estaba mal:
>
> | Afirmación anterior | Realidad |
> |---|---|
> | «No hay percentiles» | Los hay: API Gateway y Lambda los publican |
> | p95 del backend = 539 ms | **2.467 ms** — el 539 salía de otra ventana |
> | 44 peticiones de usuario al día | **~11/día** llegan a la función |
> | 4 respuestas 404 en `/api/strongs/H430/details` | **No existe ninguna**: error de tabulación |
> | Latencia por endpoint: imposible | **Resuelta** con el access log del stage, activado hoy |
>
> Lo que sigue siendo cierto y conviene no perder de vista: **ninguna de las tres métricas de
> latencia mide la petición de un usuario real sin contaminación**. Cada una mezcla una
> población distinta, y el apartado 3.1 lo desglosa.

---

## 1. Identificación

| Campo | Valor | |
|---|---|---|
| Servicio | `bible-references` — API de lectura de texto bíblico y códigos Strong | ✔ |
| Responsable | Una persona, sin equipo ni guardia | ✔ |
| Fecha de la versión | 2026-09-01 | |
| Estado | **Borrador**: describe la realidad, no compromisos acordados | |
| Documentos relacionados | `aws-architecture.md`, `wiki/`, `templates/requirements.md` | ✔ |

### Alcance

- **Entra:** la API `/api/*` servida por CloudFront → API Gateway → Lambda → RDS, y su base de
  datos.
- **No entra:** el frontend (`biblia-frontend`, repositorio y pipeline propios), aunque
  comparta distribución de CloudFront.

### Perfil de uso

Ventana fija **2026-08-26 02:42 → 2026-09-02 02:42 UTC**. Todas las cifras del documento usan
esta misma ventana; mezclarlas con otra fue el origen de los errores de la primera versión.

| Campo | Valor | |
|---|---|---|
| Peticiones al stage | **681 en 7 días** ≈ 97/día ≈ **0,001 rps de media** | ✔ |
| De ellas, llegan a la Lambda | **101 (15 %)**. Las otras **580 las rechaza API Gateway sin invocar la función** | ✔ |
| Respuestas 4xx | **604** = 580 (rechazos de API Gateway) + 24 (filtros de origen de la aplicación) | ✔ |
| Respuestas 5xx | **1 en 7 días** | ✔ |
| Respuestas 200 en `/api` | **76** | ✔ |
| Invocaciones de la Lambda | **1.193**, de las cuales **1.092 (92 %) son `bible-keep-warm`** contra `/actuator/health` | ✔ |
| Errores y throttles de la Lambda | **0 y 0** | ✔ |
| Peticiones reales de usuario | **~11/día llegan a la función**, y una parte de esas fueron pruebas propias esta semana | ✔ |
| Usuarios concurrentes | Sin dato: sin logs de acceso del CDN no hay sesiones que contar | ✖ |
| Proporción lectura / escritura | **100 / 0** | ✔ |
| Crecimiento a 12 meses | Sin previsión escrita | ✖ |

**La aritmética cuadra al dígito**, y esa es la prueba de que el modelo es correcto: el log de
la aplicación registró **exactamente 101** peticiones a `/api`, y 604 = 580 + 24.

**El tráfico real de usuario es minúsculo y está enterrado en ruido**: de cada 100 peticiones
que llegan al stage, 89 son 4xx, y de cada 100 invocaciones de la función, 92 son el ping de
calentamiento. Cualquier percentil que no separe esas tres poblaciones mide otra cosa.

> Lo que sigue sin saberse es el lado humano: cuántas personas distintas hay detrás de esas
> ~11 peticiones diarias, y si alguien intenta usar la API de noche. Eso exige logs de acceso
> del CDN, que el plan gratuito no permite.

---

## 2. Convenciones de medida

| Concepto | Acordado | |
|---|---|---|
| Punto de medida | **Origen**: API Gateway y Lambda. El borde no se puede medir | ✔ |
| Ventana de evaluación | 30 días naturales | ▷ |
| Percentiles seguidos | `p50/p90/p95/p99` de `Latency`, `IntegrationLatency` y `Duration`. Desde el 2026-09-01, también **por endpoint** vía el access log del stage | ✔ |
| Qué cuenta como error | 5xx y 504 por integración; los 403 de los filtros de origen **no** | ▷ |
| Peticiones excluidas | `bible-keep-warm` (invoca el alias directamente, no pasa por API Gateway) | ✔ |
| Fuente de verdad | CloudWatch: `Count`, `4XXError`, `5XXError`, `Latency` e `IntegrationLatency` del stage; `Duration`, `Invocations`, `Errors` y `Throttles` de la función; Logs Insights para el desglose por ruta | ✔ |

**Restricción de fondo:** los logs de acceso de **CloudFront** siguen bloqueados por el plan
gratuito. Sin ellos no hay latencia desde el borde, ni forma de saber a quién bloqueó una regla
del WAF. Lo que sí se cerró el 2026-09-01 es el tramo del origen: el access log del stage de API
Gateway da ruta, IP, agente y latencia por petición.

---

## 3. Latencia

### 3.1 Percentiles reales de CloudWatch

Ventana fija **2026-08-26 02:42 → 2026-09-02 02:42 UTC**, en milisegundos. **Existen tres
métricas de latencia y cada una está contaminada por una población distinta**; ninguna aísla
por sí sola la petición de un usuario real.

| Métrica | Qué mide | n | p50 | p90 | p95 | p99 | máx | Contaminada por | |
|---|---|---|---|---|---|---|---|---|---|
| API GW `Latency` | Extremo a extremo en el stage | 681 | **4** | 84 | 367 | 1.996 | 6.565 | Los 580 rechazos que no llegan a la Lambda, muy rápidos: por eso el p50 es de 4 ms | ✔ |
| API GW `IntegrationLatency` | Lo que tarda el backend visto por API Gateway | 101 | **120** | 1.767 | **2.467** | 3.628 | 6.535 | Los 24 rechazos de los filtros de origen, y sobre todo los arranques en frío | ✔ |
| Lambda `Duration` | Ejecución de la función | 1.193 | **24** | 141 | 591 | 1.549 | 5.762 | Los 1.092 pings de calentamiento (92 % del total) | ✔ |

**`IntegrationLatency` es la única que describe el backend**: excluye el calentamiento y excluye
lo que API Gateway rechaza sin invocar. Y su forma dice algo incómodo: p50 de **120 ms** frente
a p90 de **1,8 s**. Esa distancia no es variabilidad, es **una población distinta metida
dentro**: los arranques en frío.

> **Corrección respecto a la versión anterior de este documento.** Ahí figuraba un p95 de 539 ms
> para `IntegrationLatency`. Estaba calculado sobre otra ventana —una que incluía el 25 de
> agosto, un día en que 197 peticiones llegaron todas a la integración y arrastraron la
> distribución hacia abajo—. Con la ventana alineada al resto del documento, **el p95 real es de
> 2.467 ms**.

### 3.2 Arranque en frío: la cifra documentada era otra cosa

| Medida | Valor | n | |
|---|---|---|---|
| `Restore Duration` de SnapStart | **p50 635 ms · p90 705 ms · máx 763 ms** | 124 restauraciones en 7 días (~18/día) | ✔ |

> **Corrección.** La documentación del proyecto habla de «2,4 s de mediana y hasta 3,7 s» para
> SnapStart. Eso es la **primera petición completa** medida desde fuera, no la restauración: la
> restauración en sí son ~0,64 s. El resto es el trabajo de la primera invocación —reanudar el
> pool, JIT— más la red. Conviene no citar los 2,4 s como si fueran coste de SnapStart.

### 3.3 Por qué el p90 del backend está en 1,8 segundos

Las dos cifras de arriba se explican entre sí. Hay **~18 restauraciones al día** y solo **~11
peticiones reales de usuario al día**: es decir, el tráfico es tan escaso que **una fracción
grande de las peticiones reales cae en un entorno que acaba de restaurarse**.

No es un problema de rendimiento del código: el p50 de 120 ms dice que, en caliente, la
aplicación responde bien. Es un problema de **densidad de tráfico**, y la mitigación conocida ya
está identificada: ampliar la ventana de `bible-keep-warm` de 09:00–21:55 a 07:40–23:10 para
cubrir toda la franja en que RDS está arriba. No lo elimina —el entorno también se recicla
dentro de la ventana caliente— pero recorta las dos franjas de 2 h 35 min en que hoy **toda**
petición paga restauración.

### 3.4 Reparto por estado

Del log de acceso de la aplicación, misma ventana. Son los únicos estados que existen:

| Estado | Peticiones | Qué son | |
|---|---|---|---|
| 200 | **1.167** | 1.091 de `/actuator/health` (calentamiento) + 76 de `/api` | ✔ |
| 403 | **24** | Filtros de origen: 10 desde `www` (raspador), 4 desde el dominio `cloudfront.net`, el resto sin cabecera o pruebas propias | ✔ |
| 500 | **1** | Un único error en 7 días | ✔ |

> **Corrección respecto a la versión anterior.** Ahí se afirmaba que había 4 respuestas 404 en
> `/api/strongs/H430/details`. **No existe ninguna**: fue un desajuste de columnas al tabular la
> salida de Insights. Agrupando solo por estado, en siete días la aplicación no ha devuelto un
> solo 404.

### 3.5 Objetivos

**No hay objetivos acordados.** Propuesta, anclada a lo medido:

| Ámbito | p50 ▷ | p95 ▷ | p99 ▷ | Real hoy | Cómo se verifica |
|---|---|---|---|---|---|
| Backend (`IntegrationLatency`) | 150 ms | 2,5 s | 4 s | p50 120 · p95 2.467 · p99 3.628 | Directo, la métrica existe |
| Backend **en caliente** | 150 ms | 400 ms | 800 ms | Sin separar todavía | Log de acceso del stage, filtrando restauraciones |
| Restauración de SnapStart | 650 ms | 750 ms | 800 ms | p50 635 · p90 705 · máx 763 | Insights sobre `Restore Duration` |

El p95 propuesto de 2,5 s **no es un objetivo ambicioso, es la realidad de hoy escrita como
umbral**. Lo honesto es fijar un objetivo agresivo para el caso caliente y tratar el arranque en
frío como una población aparte, en vez de esconder los dos en un solo número.

### 3.6 Medición por endpoint: resuelta el 2026-09-01

`metricsEnabled` sigue en `false` —las métricas por método de API Gateway cuestan ~6 USD/mes
estimados, contra una factura mensual de menos de 12—. La alternativa resultó mejor y más
barata: **se activó el access logging del stage**. ✔

| Elemento | Valor |
|---|---|
| Log group | `/aws/apigateway/bible-references-api/prod`, retención **14 días** |
| Rol | `APIGatewayCloudWatchLogsRole`, registrado a nivel de cuenta |
| Campos | `requestId`, `ip`, `agente`, `metodo`, `ruta`, `recurso`, `estado`, `integracion`, `latencia`, `latenciaIntegracion`, `bytes`, `error` |
| Coste | ~1 MB/mes: céntimos |

Con eso se obtiene, por petición y sin tocar el código: la ruta pedida **aunque no exista**, si
llegó o no a la función (`integracion` en `-`), y **la latencia de integración por endpoint**,
que era el dato que faltaba. La opción que había propuesto antes —añadir el tiempo a la línea de
log de la aplicación— ya no hace falta.

---

## 4. Rendimiento y capacidad

| Métrica | Objetivo | Límite duro | Origen | |
|---|---|---|---|---|
| Peticiones sostenidas | Sin objetivo | **10 rps** | Throttling del stage `prod` | ✔ |
| Ráfaga | Sin objetivo | **20** | Throttling del stage | ✔ |
| Ejecuciones concurrentes | — | **12** | Concurrencia reservada de la Lambda | ✔ |
| Tiempo máximo de ejecución | — | **30 s** | Timeout de la función | ✔ |
| Memoria | — | 2048 MB | Configuración de la función | ✔ |
| Tamaño de respuesta | < 3 MB | **6 MB** | Invocación síncrona de Lambda | ✔ |
| Versículos por búsqueda | 500 por defecto | **2.000** | `IBibleService.MAX_SEARCH_LIMIT`; ~1,4 KB/versículo ⇒ ~2,8 MB | ✔ |
| Apariciones en `/strongs/{code}/details` | — | **2.000**, con `truncated` | `IStrongService.MAX_DETAIL_KEYWORDS`; ~330 B/aparición ⇒ ~0,7 MB | ✔ |
| Conexiones a RDS | — | Techo efectivo = 12 entornos × pool | ✔ |
| Filas en `bible_verses` | — | ~31.000 | ✔ |

**Comportamiento al saturar:** rechazar rápido. 429 en el stage sin invocar la función; el WAF
del borde bloquea antes con 403. Es deliberado: cada rechazo que ocurre **después** de invocar
la Lambda se factura igual que una petición legítima.

---

## 5. Disponibilidad

### 5.1 Ventana de servicio

| Campo | Valor | |
|---|---|---|
| Horario comprometido | **07:40–23:10 (America/Mexico_City)**, todos los días | ✔ |
| Fuera de esa franja | RDS **parada a propósito** (`bible-rds-stop` / `bible-rds-start`) | ✔ |
| Disponibilidad bruta 24×7 equivalente | **~64,6 %** (15 h 30 min de 24 h) | ✔ (derivado) |
| ¿La parada cuenta como indisponibilidad? | **No.** Es una decisión de coste, no una avería | ✔ |

> Este es el ejemplo de por qué la ventana se define antes que el porcentaje: sin acotarla,
> cualquier SLO de dos nueves sería falso de partida.

### 5.2 Objetivo

**No hay SLO acordado.** Dentro de la ventana de servicio, la propuesta:

| Componente | SLO ▷ | Definición de «disponible» | Cómo se mide | |
|---|---|---|---|---|
| API `/api/*` | 99,0 % | 2xx o 4xx de negocio ante una petición bien formada | `Count` menos `5XXError` del stage | ✔ (métrica existe) |
| Frontend (S3 + CDN) | 99,9 % | — | Fuera de alcance de este documento | |
| RDS | Igual que la API | Instancia `available` dentro de la ventana | `describe-db-instances` | ✔ |

### 5.3 Tolerancia a fallos

| Escenario | Comportamiento hoy | |
|---|---|---|
| Caída de una zona | **Caída del servicio.** RDS es Single-AZ | ✔ |
| RDS no alcanzable | 500 en ~5 s: `connection-timeout` de Hikari a 5 s y `connectTimeout` de pgjdbc a 2 s, puestos precisamente para no colgarse 30 s | ✔ |
| Petición fuera de la ventana | 500 rápido, no 504 tras medio minuto | ✔ |
| Despliegue defectuoso | `update-alias` a la versión 17 (sirve la 18 desde el 2026-11-22) | ✔ |
| Pérdida de la función | Reconstrucción manual: **no hay infraestructura como código con estado adoptado** | ✔ |

---

## 6. Presupuesto de error

Ventana de servicio: 15 h 30 min/día × 30 días = **27.900 min/mes**.

| SLO | Presupuesto mensual dentro de la ventana |
|---|---|
| 99,0 % ▷ | 279 min (4 h 39 min) |
| 99,5 % | 139 min (2 h 19 min) |
| 99,9 % | 28 min |

| Campo | Valor | |
|---|---|---|
| SLO elegido | Ninguno acordado | ✖ |
| Consumo actual | **No se mide.** No hay panel ni cálculo de presupuesto | ✖ |
| Política de congelación | No existe | ✖ |

> Con un solo operador y sin guardia, una política formal de congelación probablemente sea
> ceremonia inútil. Lo que sí falta y sí serviría es **saber cuánto se ha fallado**: hoy no hay
> forma de responder a eso.

---

## 7. Consistencia de datos

| Dato | Modelo | Ventana | |
|---|---|---|---|
| Versículos, keywords, palabras Strong | Eventual | Hasta **24 h** | ✔ |
| — | Es dato de referencia que **no cambia**: el desfase solo importa al corregir una errata | ✔ |

### Cachés declaradas

| Caché | Ámbito | TTL | Clave                              | Invalidación | |
|---|---|---|------------------------------------|---|---|
| CloudFront | Global | 86.400 s | Ruta, **sin query strings**        | Invalidación manual | ✔ |
| Caffeine `chapters` | Por instancia | 24 h | `idBible_idBook _chapter_idVerse`  | Expiración o reciclado del entorno | ✔ |
| Caffeine `strongStats` | Por instancia | 24 h | Código en mayúsculas               | Ídem | ✔ |
| Caffeine `strongDetails` | Por instancia | 24 h | Código + transliterada + traducida | Ídem | ✔ |

Límite común: `maximumSize=500`, `expireAfterWrite=24h`. Solo entra lo que autorizan
`VerseCacheCondition` (9 capítulos) y `WordCacheCondition` (3 códigos). ✔

**Endpoints con `no-store` deliberado**, porque la clave de CloudFront no lleva query strings y
cachearlos serviría la respuesta de una petición a otra: `/api/bible/search` y
`/api/strongs/{code}/details`. ✔

### Transacciones

| Campo | Valor | |
|---|---|---|
| Operaciones atómicas | Ninguna: no hay escrituras | ✔ |
| Idempotencia | Trivial: todo son `GET` | ✔ |
| Transacciones | `@Transactional(readOnly = true)`, y la caché envuelve por fuera (`@EnableCaching(order = HIGHEST_PRECEDENCE)`), de modo que un acierto no abre transacción | ✔ |

---

## 8. Durabilidad y recuperación

| Campo | Objetivo ▷ | Real | |
|---|---|---|---|
| RPO | 24 h | Copias automáticas, retención **7 días** | ✔ |
| RTO | 4 h | **Sin medir**: nunca se ha restaurado | ✖ |
| Protección contra borrado | — | `DeletionProtection: true` | ✔ |
| Cifrado en reposo | — | Activo | ✔ |
| Última restauración probada | — | **Ninguna** | ✖ |
| Copia del artefacto desplegado | — | `bible-references-lambda.jar` (v17) y `archivo/…-v16.jar`, **pero la regla `expirar-artefactos-antiguos` borra todo el bucket a los 30 días**: sin despliegue antes del 2026-11-19, no queda ninguno. La copia que perdura es la de cada versión publicada de Lambda | ✔ |

> **El riesgo mayor no es la base de datos, es el código.** El artefacto que sirve producción
> se construye desde el árbol de trabajo local; la base de datos tiene copias y protección
> contra borrado, y el código que la consulta no.

---

## 9. Seguridad

### 9.1 Autenticación y autorización

| Campo | Valor | |
|---|---|---|
| Autenticación de usuarios | **Ninguna.** API pública de solo lectura sobre texto público | ✔ |
| Autorización | No aplica | ✔ |
| Identidad del origen | Cabecera secreta `X-Origin-Verify` inyectada por CloudFront y exigida por `OriginVerifyFilter` | ✔ |
| Identidad del cliente | `X-Client-Origin` exigida por `OriginValidationFilter` (403 sin ella) | ✔ |

### 9.2 Datos

| Categoría | ¿Se trata? | Dónde | Cifrado | |
|---|---|---|---|---|
| Datos personales | **No** | — | — | ✔ |
| Credenciales | Sí | Variables de entorno de la función | En reposo, por Lambda | ✔ |
| Datos de negocio | Texto bíblico, público | RDS | Sí, en reposo y en tránsito | ✔ |
| Logs con IP | Sí, en logs de aplicación | `/aws/lambda/bible-references` | — | ✔ |

### 9.3 Superficie expuesta

| Punto de entrada | ¿Público? | Protección | |
|---|---|---|---|
| CloudFront `escrituraclave.com` | Sí | WAF: 3 grupos gestionados + `RateLimitPorIP` | ✔ |
| **URL de ejecución de API Gateway** | **Sí** | Throttling, WAF regional y `OriginVerifyFilter` — pero el 403 llega **después** de invocar y facturar la función | ✔ |
| RDS | No | Sin IP pública, SG dedicado | ✔ |
| Rutas no declaradas | — | API Gateway responde `Missing Authentication Token` **sin invocar** la Lambda (comodín eliminado el 2026-08-25) | ✔ |

> **Un endpoint nuevo en el código exige declarar su ruta en API Gateway**, o dará 403 en
> producción aunque el código sea correcto.

### 9.4 Limitación de tasa

| Capa | Ámbito | Límite | Respuesta | ¿Invoca la función? | |
|---|---|---|---|---|---|
| WAF en CloudFront | Por IP | 150 / 5 min | 403 | No | ✔ |
| Throttling del stage | Global | 10 rps, ráfaga 20 | 429 | No | ✔ |
| WAF regional | Por IP | 500 / 5 min | 403 | No | ✔ |
| Concurrencia reservada | Global | 12 | 500 vía `Throttles` | — | ✔ |
| `OriginVerifyFilter` | Petición | — | 403 | **Sí, ya facturada** | ✔ |

Shield Standard activo y gratuito con CloudFront (L3/L4). Shield Advanced **no** contratado. ✔

### 9.5 Secretos

| Secreto | Dónde | Última rotación | |
|---|---|---|---|
| `X-Origin-Verify` | Configuración de CloudFront (sin copia local) | 2026-08-23 | ✔ |
| Contraseña de RDS | Variable de entorno de la función | **Nunca rotada** — pendiente desde que el puerto 5432 estuvo abierto a internet | ✔ |

### 9.6 Cumplimiento

Sin requisitos aplicables: no se tratan datos personales ni regulados. ✔

---

## 10. Observabilidad

| Señal | Existe | Retención | Atribución | |
|---|---|---|---|---|
| Logs de aplicación | Sí | **Sin configurar: para siempre** (3,6 MB hoy) | Solo de lo que llega a ejecutarse | ✔ |
| Logs de acceso del CDN | **No** | — | — | ✔ (bloqueado por el plan) |
| Métricas del stage y la función | Sí | Estándar de CloudWatch | No | ✔ |
| Latencia agregada (stage y función) | **Sí** | Estándar de CloudWatch | No | ✔ |
| **Logs de acceso del stage** | **Sí, desde el 2026-09-01** | **14 días** | **Sí**: IP, agente, ruta y latencia por petición | ✔ |
| Latencia por endpoint | **Sí, desde el 2026-09-01** | 14 días | Vía `latenciaIntegracion` + `recurso` del access log | ✔ |
| Trazas distribuidas | **No** | — | — | ✖ |

| Alarma | Umbral | Destinatario | |
|---|---|---|---|
| `bible-references-api-5xx` | `5XXError` ≥ 1 en 5 min | SNS `bible-references-alarms`, correo **confirmado** | ✔ nueva |
| `bible-references-api-latencia-alta` | `IntegrationLatency` **p95** > 3 s en 2 ventanas de 15 min | SNS | ✔ nueva |
| `bible-references-api-count-alto` | `Count` > **1.000** / 5 min | SNS | ✔ umbral subido desde 150 |
| `Alarma` (facturación, us-east-1) | `EstimatedCharges` > 12 USD | — | ✔ |

> **Por qué se subió el umbral de volumen.** Con el 89 % del tráfico siendo 4xx de escáneres,
> 150 peticiones en 5 minutos era un umbral que disparaba con bots y no con un problema: medía
> justo la parte ruidosa. 1.000 en 5 minutos son ~3,3 rps, un tercio del throttle del stage.
> Las dos alarmas nuevas son las accionables: un 5xx nunca lo causa un escáner, y el p95 de
> `IntegrationLatency` solo mira peticiones que llegaron a la función.

> **Corrección a `aws-architecture.md`:** ese documento dice que la suscripción al topic está
> sin confirmar. Hoy tiene ARN de suscripción real, es decir, **está confirmada**. Conviene
> actualizarlo.

**Coste:** la alarma de facturación siguió en `OK` al cerrar agosto, así que el mes terminó por
**debajo de 12 USD** — frente a los 86,45 USD de julio. ✔

---

## 11. Restricciones de arquitectura

### 11.1 Impuestas desde fuera

| Restricción | Detalle | Consecuencia | |
|---|---|---|---|
| Región | `mx-central-1` | Sin Function URLs ni HTTP API: obliga a REST API y payload v1 | ✔ |
| Plan de CloudFront | Gratuito | **Sin logs de acceso**, por las dos vías | ✔ |
| Presupuesto | Alarma a 12 USD/mes | Condiciona el apagado nocturno y el tamaño de RDS | ✔ |
| DNS | Gestionado **fuera de AWS** | No hay hosted zone en Route 53 | ✔ |

### 11.2 Límites duros de la plataforma

| Límite | Valor | Al superarlo | Margen | |
|---|---|---|---|---|
| Payload de invocación síncrona | 6 MB | 502 y se lleva por delante el entorno | `MAX_SEARCH_LIMIT` acota a ~2,8 MB | ✔ |
| Timeout de la función | 30 s | 504 | Amplio | ✔ |
| Concurrencia reservada | 12 | `Throttles` → 500 | Sin dato de uso real | ✔ |
| Trigramas | 3 caracteres | Búsquedas de 1–2 letras no usan índice | Inevitable | ✔ |

### 11.3 Decisiones tomadas que no se renegocian

| Decisión | Motivo | Fecha | Reversible si… | |
|---|---|---|---|---|
| Apagado nocturno de RDS | Coste; sin usuarios de noche | 2026-08-13 | Aparecen usuarios en otra franja | ✔ |
| Borrado del ALB | 28,3 USD/mes en fontanería sin tráfico medible | 2026-08-15 | Se vuelve a ECS | ✔ |
| Arquitectura x86, no ARM64 | Con Spot el ahorro caía a ~1,44 USD/mes y arrastraba QEMU al pipeline | 2026-08-13 | — | ✔ |
| Caffeine en vez de caché distribuida | Una sola instancia lógica; multicast no funciona en VPC | 2026-08-13 | — | ✔ |

### 11.4 Restricciones de operación

**La sección más determinante de todo el documento.**

| Campo | Valor | Consecuencia | |
|---|---|---|---|
| Personas operando | 1 | Cualquier diseño que exija guardia es inviable | ✔ |
| Guardia | Ninguna | Una caída nocturna se descubre por la mañana | ✔ |
| Despliegue | **Manual**, desde el portátil | El pipeline de GitHub Actions despliega a ECS, que tiene `desiredCount 0`: no actualiza producción | ✔ |
| Vuelta atrás | `update-alias` a la versión 17 | Segundos; dos escalones (17 y 16; 13–15 borradas el 2026-10-20) | ✔ |
| Infraestructura como código | Terraform **sin estado adoptado** | Hoy es la vía de reconstrucción, no el seguro | ✔ |
| Migraciones de esquema | **Ninguna herramienta.** `ddl-auto: none`, sin Flyway | Los cambios se aplican a mano, en orden, desde `docs/sql/` | ✔ |
| Código en producción | **No versionado**: se construye del árbol local | Un accidente en la carpeta y no hay forma de reconstruirlo | ✔ |

### 11.5 Dependencias externas

| Dependencia | Criticidad | Si cae | |
|---|---|---|---|
| CloudFront | Alta | Sin servicio | ✔ |
| API Gateway REST | Alta | Sin API | ✔ |
| RDS PostgreSQL | Alta | 500 en ~5 s | ✔ |
| Registro DNS externo | Alta | Sin resolución del dominio | ✔ |

---

## 12. Compromisos aceptados

| Se prioriza | Se sacrifica | Motivo | Fecha | Revisar si… |
|---|---|---|---|---|
| Coste | Disponibilidad 24×7 | Sin usuarios de noche | 2026-08-13 | Aparecen usuarios en otra franja |
| Coste | Observabilidad (logs del CDN) | El plan gratuito no los permite | — | Hace falta atribuir tráfico |
| Coste | Resiliencia multi-AZ | Single-AZ en `db.t4g.micro` | — | El servicio deja de ser prescindible unas horas |
| Simplicidad | Despliegue reproducible | Publicación manual, sin IaC con estado | — | **Ya conviene revisarlo** |
| Latencia en frío | Coste de calentamiento | Ventana de *keep-warm* más corta que la de RDS | — | Molesten los 3 s en las franjas de borde |

---

## 13. Supuestos y riesgos

| Supuesto | Si es falso | Cómo se comprobaría |
|---|---|---|
| No hay usuarios entre 23:10 y 07:40 | Se les está negando el servicio sin saberlo | Requiere logs de acceso: hoy no se puede |
| El tráfico está muy por debajo de los límites | Los rechazos serían invisibles | `Throttles` y `4XXError` del stage |
| El texto bíblico no cambia | El TTL de 24 h sería excesivo | — |

| Riesgo | Impacto | Mitigación |
|---|---|---|
| **El código de producción solo existe en el portátil** | Pérdida total de la capacidad de reconstruir | Ninguna hoy |
| Terraform sin estado adoptado | Reconstrucción manual desde documentación | Los runbooks |
| Contraseña de RDS sin rotar | Credencial posiblemente expuesta en el pasado | Ninguna hoy |
| Logs de la Lambda sin retención | Crecen sin límite | Ninguna hoy |
| Sin restauración probada | El RPO de 7 días es una suposición | Ninguna hoy |

---

## 14. Criterios de aceptación

| # | Requisito | Umbral | Verificación | Estado |
|---|---|---|---|---|
| NFR-01 | La API responde dentro de la ventana de servicio | 2xx | `curl` con `X-Client-Origin` | ✔ Cumple (2026-09-01) |
| NFR-02 | La búsqueda no supera el payload de Lambda | < 6 MB | `MAX_SEARCH_LIMIT` = 2.000 | ✔ Cumple |
| NFR-03 | La búsqueda usa índice | `Bitmap Index Scan` | `docs/sql/03-explain.sql` | ✔ Cumple (2026-09-01) |
| NFR-04 | El origen no es alcanzable sin el secreto del borde | 403 | Petición directa a la URL de ejecución | ✔ Cumple |
| NFR-05 | La base de datos no es alcanzable desde internet | Sin IP pública | `describe-db-instances` | ✔ Cumple |
| NFR-06 | Existe versión anterior para vuelta atrás | ≥ 1 | `list-versions-by-function` | ✔ Cumple (16, 17 y 18, 2026-11-22) |
| NFR-07 | Coste mensual bajo el umbral | < 12 USD | Alarma de facturación | ✔ Cumple (agosto) |
| NFR-08 | Latencia p95 del backend | ▷ 2,5 s | `IntegrationLatency`: **2.467 ms** | ✔ Cumple (umbral fijado sobre lo real) |
| NFR-08b | Latencia p95 **por endpoint** | ▷ Por definir | Access log del stage, desde el 2026-09-01 | ✔ Instrumentado; falta acordar umbral |
| NFR-09 | Disponibilidad dentro de la ventana | ▷ Por definir | `Count` − `5XXError` | ✖ Sin panel |
| NFR-10 | Copia restaurable | Restauración probada | Restauración a instancia temporal | ✖ Nunca probada |
| NFR-11 | Código de producción reconstruible | Artefacto trazable a una fuente | — | ✖ **Incumple** |
| NFR-12 | Secretos rotados | ≤ 12 meses | — | ✖ Incumple: contraseña de RDS |

---

## 15. Registro de cambios

| Fecha | Cambio | Motivo |
|---|---|---|
| 2026-09-01 | Primera instancia de la plantilla, con el estado real verificado contra la API de AWS | Tener línea base antes de acordar objetivos |
| 2026-09-01 | Percentiles reales de CloudWatch; ventana fijada; corregidos el p95 del backend (539 ms → 2.467 ms) y unos 404 inexistentes; access logging del stage activado; alarmas rehechas | Los primeros números mezclaban ventanas y una tabulación errónea inventó estados que no existían |
| 2026-10-20 | Vuelta atrás y NFR-06 apuntan a la 16 (sirve la 17; 13–15 borradas); tope de apariciones del detalle Strong en §4; la copia en S3 del artefacto caduca a los 30 días | Las versiones citadas ya no existían. **Solo se tocaron esas filas**: el resto del documento sigue describiendo el estado del 2026-09-01 |
| 2026-11-22 | Vuelta atrás y NFR-06 apuntan a la 17 (sirve la 18) | Publicada la 18. Solo se tocaron esas filas |
