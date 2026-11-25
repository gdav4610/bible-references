# Arquitectura AWS — bible-references

Cuenta `274869222183` · Región `mx-central-1` (México) · VPC `vpc-0a224eceeb1c0720a`

**Verificado contra la infraestructura real el 2026-08-25** con comandos `describe-*` /
`get-*` de la AWS CLI (identidad `arn:aws:iam::274869222183:user/gdgr`). Los nombres, IDs,
umbrales y estados de este documento vienen de la API de AWS, no de inferencias sobre el
repositorio ni de lo que dijeran versiones anteriores.

> **Qué cambió respecto a la versión anterior.** Aquel documento describía la arquitectura
> ECS + ALB, borrada en agosto de 2026, y arrastraba avisos sobre recursos inexistentes. Se
> conserva íntegro en **`aws-architecture-with-ecs-alb.md`** como registro histórico. Este ocupa
> su lugar y describe **solo lo que existe hoy**, remitiendo a los documentos especializados
> para el detalle.

## Índice de documentos

| Para saber | Ir a |
|---|---|
| Cómo se limita la tasa y por qué esos umbrales | `rate-limiting-lambda.md` |
| Por qué se migró a Lambda y con qué consecuencias | `migracion-lambda.md` |
| Qué había configurado en el ALB al borrarlo | `alb-borrado-configuracion.md` |
| Cómo volver atrás desde Lambda | `runbook-vuelta-atras-lambda.md` |
| Qué se apagó para bajar la factura | `runbook-ahorro-costos.md` |
| El bucle de reinicio del servicio ECS | `runbook-hallazgo-0-bucle-reinicio.md` |
| Cómo el ALB era alcanzable saltándose el CDN | `runbook-hallazgo-2-alb-bypass.md` |
| Convenciones de código y de PR | `approach-spec-driven-development.md` |

## Camino real del tráfico

```
Navegador
   │  DNS gestionado FUERA de AWS (no hay hosted zone en Route 53)
   ▼
CloudFront  E1MTT1XP2UUMYU
   │  escrituraclave.com · www.escrituraclave.com
   │  WAF CreatedByCloudFront-228216dd  (3 grupos gestionados + RateLimitPorIP)
   │  DefaultRootObject index.html · sin CustomErrorResponses
   │
   ├── comportamiento por defecto ──► S3 biblia-frontend-prod
   │       función viewer-request: biblia-spa-router (LIVE) — enrutado de la SPA
   │
   └── /api/*  ──────────────────────► API Gateway  m9zcdngc1m  stage prod
           OriginPath /prod                REST API v1, REGIONAL, 4 rutas explícitas
           inyecta X-Origin-Verify         throttling 10 rps / ráfaga 20
           CachePolicy CachingDisabled     WAF bible-references-api-acl
                                             │
                                             ▼
                                    Lambda bible-references : live → versión 17
                                       java25 · 2048 MB · timeout 30 s · x86_64
                                       SnapStart en versiones publicadas
                                       concurrencia reservada 12
                                       en VPC, sg bible-lambda-sg
                                       OriginVerifyFilter → 403 sin X-Origin-Verify
                                       OriginValidationFilter → 403 sin Origin
                                             │
                                             ▼
                                    RDS database-2 · PostgreSQL 17.9
                                       db.t4g.micro · 20 GB · sg bible-rds-sg
                                       privada, cifrada, backups 7 d, deletion protection
```

**La URL de ejecución de API Gateway es pública.** Quien la conozca alcanza la Lambda sin pasar
por CloudFront; lo que lo detiene es `OriginVerifyFilter`, que responde 403 —pero **después** de
que la función se haya invocado y facturado—. Las capas 2 y 3 del rate limiting existen por eso.

**Solo las cuatro rutas reales llegan a la función.** Desde el 2026-08-25 no hay comodín: API
Gateway rechaza cualquier otra ruta con `{"message":"Missing Authentication Token"}` sin invocar
la Lambda. Eso elimina el coste de los barridos de escáner, que pedían rutas inexistentes.

> ⚠️ **Un endpoint nuevo en el código exige declarar su ruta en API Gateway.** Si no, dará 403 en
> producción aunque el código sea correcto.

Detalle completo de ambas cosas en `rate-limiting-lambda.md`.

## Inventario de lo que existe

### Cómputo y entrega

| Recurso | Identificador | Estado |
|---|---|---|
| Distribución CloudFront | `E1MTT1XP2UUMYU` | `Deployed`, habilitada |
| Función CloudFront | `biblia-spa-router` | `LIVE`, en viewer-request del comportamiento por defecto |
| API Gateway REST | `m9zcdngc1m` / stage `prod` | REGIONAL, **cuatro rutas explícitas**; el comodín `/{proxy+}` se eliminó el 2026-08-25 |
| Función Lambda | `bible-references` | alias `live` → versión **13** |
| Versiones Lambda | `$LATEST` y `13` | **sin versión de vuelta atrás**: la 11 y la 12 se borraron el 2026-08-23 |

### Datos y almacenamiento

| Recurso | Identificador | Notas |
|---|---|---|
| RDS PostgreSQL | `database-2` | 17.9, `db.t4g.micro`, 20 GB, cifrada, backups **7 días**, deletion protection **activa**, sin acceso público, Single-AZ |
| S3 frontend | `biblia-frontend-prod` | origen del comportamiento por defecto |
| S3 artefactos | `bible-references-lambda-artifacts-274869222183` | JAR desde el que se publica la Lambda |
| S3 logs de CDN | `bible-references-cf-logs-274869222183` | creado para logs de acceso que **el plan gratuito no permite activar**; sin uso |
| ECR | `bible-references`, `biblia-frontend` | imágenes de la etapa ECS |

### Programación (EventBridge Scheduler, zona `America/Mexico_City`)

| Nombre | Estado | Expresión | Efecto |
|---|---|---|---|
| `bible-rds-start` | ✅ ENABLED | `cron(40 7 * * ? *)` | arranca RDS a las **07:40** |
| `bible-rds-stop` | ✅ ENABLED | `cron(10 23 * * ? *)` | para RDS a las **23:10** |
| `bible-keep-warm` | ✅ ENABLED | `cron(0/5 9-21 * * ? *)` | invoca `bible-references:live` cada 5 min, **09:00–21:55** |
| `bible-ecs-start` | ⛔ DISABLED | — | — |
| `bible-ecs-stop` | ⛔ DISABLED | — | — |

> **La API no funciona de 23:10 a 07:40.** Es una decisión de coste deliberada, no una avería.
>
> **Y hay dos huecos en los que RDS está arriba pero no hay entorno caliente**: de 07:40 a 09:00
> y de 21:55 a 23:10 —unas 2 h 35 min al día—. Las peticiones en esas franjas pagan arranque en
> frío (~3 s). Ampliar la ventana de `bible-keep-warm` los cerraría y el coste cabe en el
> *free tier*; está pendiente.

### Red

| Grupo de seguridad | ID | Uso actual |
|---|---|---|
| `bible-lambda-sg` | `sg-0127ad7198071deec` | la función, sin reglas de entrada |
| `bible-rds-sg` | `sg-05d935e48e7d6fb1f` | la base de datos |
| `bible-ecs-sg` | `sg-09f1e2be72d76adbe` | ECS, hoy parado |
| `bible-alb-sg` | `sg-0f48e25801a170bef` | **huérfano** — el ALB ya no existe |
| `default` | `sg-050bc646783252d10` | de la VPC |

La Lambda corre en las subredes `subnet-05d58e02a68c1526d` y `subnet-0d422b51e4fa87fcd`.

### Identidad

| Usuario | Llave | Creada | Último uso |
|---|---|---|---|
| `gdgr` | `AKIAT7733PMTYV3YEEFV` | 2021-08-27 | **2025-07-18** — más de un año sin usarse |
| `github-actions-biblia` | `AKIAT7733PMTZSKOT2WZ` | 2026-04-07 | pipeline de backend |
| `github-actions-biblia-frontend` | `AKIAT7733PMTZ6GMAXFI` | 2026-04-08 | pipeline de frontend |

## Lo que ya no existe

| Recurso | Qué pasó |
|---|---|
| ALB `bible-alb` | **borrado el 2026-08-15** con sus dos IPv4 (~28,3 USD/mes menos). Configuración en `alb-borrado-configuracion.md` |
| Listener y regla de `X-Origin-Verify` del ALB | desaparecieron con él; la comprobación la hace ahora `OriginVerifyFilter` en la aplicación |
| Bucket `testbucketfordataassessments` | ya no aparece en la cuenta |

### Restos huérfanos

No cuestan dinero apreciable, pero confunden a quien lea el inventario:

- **Target group `bible-tg`** — sin balanceador al que colgarse.
- **Security group `bible-alb-sg`** — sin ALB.
- **Repositorio ECR `biblia-frontend`** — el frontend se sirve desde S3.

## ECS: declarado pero apagado

| | |
|---|---|
| Cluster | `biblia-cluster` |
| Servicio | `bible-references-service`, `ACTIVE` |
| Tareas | `desiredCount 0`, `runningCount 0` |
| Task definition | `bible-references-task:22` |
| Logs | `/ecs/bible-references`, retención 14 días |

**Ambos programadores de ECS están desactivados y el servicio pide cero tareas**, así que ECS no
arranca ni por la noche ni por el día: está simplemente parado. Versiones anteriores de este
documento decían que se paraba cada noche y no volvía; hoy la situación es más simple y más
estable, pero el efecto práctico es el mismo: **ECS no sirve tráfico**.

Se conserva porque es la vía de vuelta atrás si Lambda diera problemas. Ver
`runbook-vuelta-atras-lambda.md` — con el ALB borrado, esa vuelta **ya no son diez minutos**.

## Seguridad

Cuatro capas de limitación de tasa y dos de verificación de origen. El detalle —umbrales,
razones, restricciones del plan, verificación y reversión— está en **`rate-limiting-lambda.md`**;
aquí solo el mapa:

| Capa | Dónde | Qué hace |
|---|---|---|
| WAF del borde | CloudFront | 3 grupos gestionados + `RateLimitPorIP` 150/IP cada 5 min |
| Throttling de stage | API Gateway | 10 rps, ráfaga 20 → 429 sin invocar la Lambda |
| WAF regional | stage `prod` | `RateLimitPorIP` 500/IP cada 5 min |
| Concurrencia reservada | Lambda | 12 simultáneas; techo de gasto y de conexiones a RDS |
| `OriginVerifyFilter` | aplicación | exige el secreto que inyecta CloudFront |
| `OriginValidationFilter` | aplicación | exige `Origin` / `X-Client-Origin` del frontend |

**Shield Standard** está activo y es gratuito con CloudFront (cubre L3/L4). **Shield Advanced no
está contratado.**

**Consecuencia de diseño:** como CloudFront y la API comparten origen, **el CORS no interviene en
el camino de producción**. La configuración de orígenes permitidos sirve para desarrollo local y
como defensa en profundidad, no es lo que habilita el tráfico real.

## Observabilidad

| Elemento | Estado |
|---|---|
| Alarma `bible-references-api-count-alto` | `mx-central-1` — `Count` del stage > 150 en 5 min |
| Topic SNS `bible-references-alarms` | `mx-central-1` → `gdav4610@gmail.com` · ⚠️ **suscripción sin confirmar** |
| Alarma `Alarma` (facturación) | `us-east-1` — `EstimatedCharges` > 12 USD → topic `ALARM` |
| Logs de la Lambda | `/aws/lambda/bible-references` — **sin retención configurada: se guardan para siempre** |
| Logs de ECS | `/ecs/bible-references` — 14 días |
| Logs de acceso de CloudFront | ❌ bloqueados por el plan gratuito, por las dos vías |

**Sin logs de acceso no se puede saber quién genera el tráfico.** Es la limitación de fondo de
todo el diagnóstico: cuando una regla del WAF bloquea, no hay forma de saber a quién. Lo único
que da atribución hoy son los logs de la aplicación, y solo para lo que llega a ejecutarse.

## Despliegue

**Frontend:** pipeline propio a `biblia-frontend-prod` + invalidación de CloudFront.

**Backend:** aquí hay una discrepancia que conviene tener presente.

`.github/workflows/aws-dep.yml` se llama *«Deploy to AWS ECS»*, se dispara con cada `push` a
`main` y despliega al servicio ECS. Pero **ECS no sirve tráfico**: lo sirve la Lambda, que se
publica **a mano** desde un JAR construido en el árbol de trabajo local y subido a
`bible-references-lambda-artifacts-274869222183`.

Es decir:

- Un `push` a `main` despliega a un servicio con `desiredCount 0`. No rompe nada, pero **tampoco
  actualiza producción**.
- **Lo que sirve producción no está en `main`.** Existe en un solo sitio: el portátil.

> El workflow además ejecuta `aws ecs describe-task-definition > task-definition.json` antes de
> renderizar, de modo que **sobrescribe el archivo versionado** con lo registrado en AWS. Editar
> ese archivo en el repositorio no tiene efecto; los cambios de CPU, memoria o health check hay
> que registrarlos como revisión nueva. Ver `approach-spec-driven-development.md`.

## Hallazgos abiertos

| # | Hallazgo | Estado |
|---|---|---|
| — | **El código que sirve producción no está commiteado** | 🔴 el mayor riesgo abierto: un accidente en la carpeta local y no hay forma de reconstruir el artefacto desplegado |
| 8 | State de Terraform sin adoptar | 🔴 dejó de ser preventivo: con el ALB borrado, **hoy es la vía de reconstrucción, no el seguro**. Nada de API Gateway, WAF regional, throttling ni concurrencia está en el módulo |
| 15 | Rotar la contraseña de RDS | 🔵 el `X-Origin-Verify` se rotó el 2026-08-23; **la contraseña sigue sin rotar** |
| 9 | Llave de acceso de `gdgr` | 🟡 activa, sin usar desde 2025-07-18 |
| — | Retención de los logs de la Lambda | 🟡 **sin configurar**: crecen sin límite |
| — | El pipeline despliega a infraestructura muerta | 🟡 ver *Despliegue* |
| — | Recursos huérfanos: `bible-tg`, `bible-alb-sg`, ECR `biblia-frontend` | 🟡 ruido en el inventario |
| 5 | Task role y execution role son el mismo | 🟡 relevante solo si vuelve ECS |
| 6 | Llaves estáticas en GitHub Actions en vez de OIDC | 🟡 |
| 11 | El cluster de ECS lo gestiona CloudFormation | 🟡 a resolver junto con el hallazgo 8 |
| 14 | Rutas inexistentes devuelven 500 en vez de 404 | ✅ resuelto para `/api/*`: API Gateway las rechaza con 403 antes de llegar a la aplicación |
| — | Barridos de escáner por el CDN | ✅ dejaron de costar invocaciones al eliminar el comodín; ver `rate-limiting-lambda.md` |
| — | Ventana de `bible-keep-warm` más corta que la de RDS | 🟡 2 h 35 min al día con arranque en frío |

### Resueltos desde la revisión anterior

| # | Hallazgo | Cómo se cerró |
|---|---|---|
| 0 | Bucle de reinicio del servicio ECS | `runbook-hallazgo-0-bucle-reinicio.md` |
| 1 | La base de datos estaba expuesta a internet | sin IP pública, SG dedicado |
| 2 | El ALB era alcanzable saltándose CloudFront | el ALB ya no existe; `OriginVerifyFilter` cubre el equivalente |
| 3 | Sin resiliencia en la capa de datos | **deletion protection activa y backups a 7 días**, verificado |
| 4 | Hazelcast por multicast | sustituido por Caffeine |
| 7 / 10 | Bucket `testbucketfordataassessments` y acceso de una cuenta ajena | el bucket ya no aparece en la cuenta |
| 12 | ECR sin caducidad y logs sin retención | resuelto para ECR y `/ecs/*`; **no para el log group de la Lambda** |
| — | Ningún límite de tasa en ninguna capa | cuatro capas hoy; ver `rate-limiting-lambda.md` |
| — | Ninguna alarma sobre el tráfico | `bible-references-api-count-alto`, pendiente de confirmar la suscripción |

## Cómo se verificó

Todo lo de este documento sale de estos comandos, ejecutados el 2026-08-25 en la cuenta
`274869222183`:

```powershell
aws cloudfront list-distributions
aws cloudfront get-distribution-config --id E1MTT1XP2UUMYU
aws cloudfront list-functions
aws apigateway get-rest-apis --region mx-central-1
aws apigateway get-stages --rest-api-id m9zcdngc1m --region mx-central-1
aws lambda get-function-configuration --function-name bible-references --region mx-central-1
aws lambda list-aliases --function-name bible-references --region mx-central-1
aws lambda list-versions-by-function --function-name bible-references --region mx-central-1
aws lambda get-function-concurrency --function-name bible-references --region mx-central-1
aws rds describe-db-instances --region mx-central-1
aws ecs list-clusters --region mx-central-1
aws ecs describe-services --cluster biblia-cluster --services bible-references-service --region mx-central-1
aws scheduler list-schedules --region mx-central-1
aws scheduler get-schedule --name <nombre> --region mx-central-1
aws elbv2 describe-load-balancers --region mx-central-1      # vacío: no hay ALB
aws elbv2 describe-target-groups --region mx-central-1
aws ec2 describe-security-groups --region mx-central-1
aws s3api list-buckets
aws ecr describe-repositories --region mx-central-1
aws iam list-users ; aws iam list-access-keys --user-name <usuario>
aws iam get-access-key-last-used --access-key-id <llave>
aws logs describe-log-groups --region mx-central-1
aws cloudwatch describe-alarms --region mx-central-1
aws route53 list-hosted-zones                                 # vacío: DNS fuera de AWS
aws wafv2 get-web-acl-for-resource --resource-arn <arn del stage> --region mx-central-1
```

> **Nada de la infraestructura descrita aquí está bajo Terraform con state adoptado.** Mientras
> siga así, este documento y los que enlaza son la única constancia de lo que existe y de cómo
> deshacerlo.
