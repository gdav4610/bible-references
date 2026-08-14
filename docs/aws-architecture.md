# Arquitectura AWS — bible-references

Cuenta: `274869222183` · Región: `mx-central-1` (México) · VPC: `vpc-0a224eceeb1c0720a`

Topología **verificada contra la infraestructura real** el 2026-07-31 mediante comandos 
`describe-*` de la AWS CLI (identidad `arn:aws:iam::274869222183:user/gdgr`). Los nombres
de recursos, IDs, reglas de red y rutas provienen de la API de AWS, no de inferencias
sobre el repositorio.

**Este documento describe el estado posterior a los cambios aplicados el 2026-07-31.**
Ver el registro de cambios más abajo.

> **Revalidado y ampliado el 2026-08-04.** Se repitieron las consultas `describe-*`, se
> ejecutó `terraform plan` y se hizo un barrido completo de objetos sin uso. El camino de
> producción **no ha cambiado** desde el 2026-07-31 —misma revisión `:21`, misma task
> corriendo desde las 22:05 de ese día, `healthStatus: HEALTHY`—, pero sí se aplicaron
> cuatro cambios de higiene: ver el registro de abajo. El módulo de `terraform/` se
> actualizó en consecuencia y sigue siendo un espejo fiel:
> `28 to import, 0 to add, 3 to change, 0 to destroy`.

## Registro de cambios aplicados (2026-07-31)

| Hora | Cambio | Recurso | Hallazgo |
|---|---|---|---|
| 20:06 | Ingress del ALB restringido a la prefix list de CloudFront; revocadas las reglas `80` y `443` desde `0.0.0.0/0` | `sg-0f48e25801a170bef` | 2, fase 1 |
| 21:08 | Registrada revisión 18 con `startPeriod: 180` y `timeout: 15` — **intento fallido**, no corrigió nada | `bible-references-task:18` | 0 |
| 21:29 | Registrada y desplegada revisión 19 **sin health check de contenedor** | `bible-references-task:19` | 0 |
| 21:40 | Header `X-Origin-Verify` inyectado en el origen `Backend-API` | CloudFront `E1MTT1XP2UUMYU` | 2, fase 2 |
| 21:44 | Regla de listener con prioridad 1 exigiendo el header; acción por defecto a `403` | Listener `:80` de `bible-alb` | 2, fase 2 |
| 21:55 | Despliegue de CI con la imagen que instala `curl` (`Dockerfile` versionado) | `bible-references-task:20` | 0 |
| 22:05 | Health check de contenedor restaurado (`startPeriod: 180`, `timeout: 15`) | `bible-references-task:21` | 0 |

Runbooks con el detalle y los procedimientos de reversión:

- `runbook-hallazgo-0-bucle-reinicio.md`
- `runbook-hallazgo-2-alb-bypass.md`
- `runbook-ahorro-costos.md` — preparado el 2026-08-13, **sin aplicar**

## Registro de cambios aplicados (2026-08-04)

Higiene, a raíz del barrido de objetos sin uso. Ninguno toca el camino de producción;
verificado después: target `healthy` y servicio 1/1.

| Hora | Cambio | Recurso | Reversible |
|---|---|---|---|
| 14:21 | Retención de logs a 14 días (antes: nunca expiraban) | `/ecs/bible-references` y `/ecs/biblia-frontend-task` | Sí, `put-retention-policy` |
| ~15:0x | Lifecycle policy que conserva las **2 imágenes más recientes**; expira 16 (~2,3 GB) | ECR `bible-references` | La política sí; las imágenes borradas **no** |
| ~15:2x | Eliminado target group huérfano | `biblia-frontend-tg` | No |
| ~15:2x | Eliminado log group huérfano | `/ecs/biblia-frontend-task` | No |
| ~15:4x | Eliminados 4 stacks de CloudFormation de SAM, con sus 2 Lambdas, 3 API Gateways y 3 roles | `sam-app`, `my-new-app`, `pets-service`, `aws-sam-cli-managed-default` (us-east-1) | No |
| ~15:4x | Eliminados 2 security groups huérfanos | `biblia-sg`, `biblia-alb-sg` | No |
| ~15:4x | Eliminadas 4 políticas IAM sin adjuntos | `testgdavpolicy`, `ListAllBuckets-test1`, 2× `AWSLambdaBasicExecutionRole-*` | No |

Sobre la lifecycle policy de ECR: se validó antes con `start-lifecycle-policy-preview`
—que no borra nada— confirmando que la imagen en producción (`0c566343…`, revisión `:21`)
y la anterior quedan entre las conservadas. ECR evalúa las políticas de forma asíncrona,
así que el borrado puede tardar hasta 24 h en reflejarse.

Conservar solo 2 imágenes deja **un único paso de rollback**: tras dos despliegues
seguidos, la versión de hace dos ya no existe en ECR y habría que reconstruirla desde el
commit. Fue una decisión explícita.

La retención de logs se aplicó desde la consola, fuera de Terraform, y el módulo declaraba
`retention_in_days = 0`. Se detectó porque `terraform plan` empezó a marcar un cuarto
cambio; se rastreó en CloudTrail y se actualizó `storage_and_secrets.tf`. Es una
ilustración exacta del hallazgo 8: **un cambio manual no lo detecta nadie hasta que alguien
vuelve a correr `plan`.**

## Registro de cambios aplicados (2026-08-13) — reducción de costes

Motivación: pocos usuarios de día, ninguno de noche, y una factura que no dependía del
tráfico. Detalle, verificación y reversión en **`runbook-ahorro-costos.md`**.

### De dónde salía la factura

Julio de 2026, cifras **reales de Cost Explorer** por `USAGE_TYPE`, antes de impuestos:

| Partida | USD | % |
|---|---|---|
| `MXC1-Fargate-vCPU-Hours` + `GB-Hours` | 23,97 | 32% |
| `MXC1-LoadBalancerUsage` (ALB, tarifa base) | 17,58 | 24% |
| `MXC1-PublicIPv4:InUseAddress` (5 direcciones) | 17,34 | 23% |
| `MXC1-InstanceUsage:db.t4g.micro` | 12,65 | 17% |
| `MXC1-RDS:GP2-Storage` | 2,42 | 3% |
| Secrets Manager, ECR, S3 | 0,58 | 1% |
| **Total sin impuestos** | **74,53** | |
| Impuestos | 11,92 | |
| **Total** | **86,45** | |

Dos hallazgos que solo aparecen al desglosar por tipo de uso:

- **`MXC1-LCUUsage` = 0,0018 USD.** El balanceador no procesa prácticamente nada. Los 17,58
  USD son tarifa base pura: cuesta lo mismo servir un puñado de peticiones que ninguna.
- **Las IPv4 públicas son 5, no 4:** el ALB ocupa **tres** subredes, más la task de Fargate
  y RDS. A 0,005 USD/hora salen 17,34 USD/mes, casi tanto como el balanceador.

Sumadas, ALB e IPv4 eran **47% de la factura** en fontanería de red para una aplicación sin
tráfico medible.

### Lo aplicado

| # | Cambio | Ahorro/mes | Estado |
|---|---|---|---|
| 1 | RDS sin IP pública, con `bible-rds-sg` dedicado | 3,47 | ✅ aplicado 17:40 |
| 2 | Servicio ECS a `FARGATE_SPOT` | ~16,80 | ✅ aplicado 17:33 |
| 4 | Apagado nocturno de ECS y RDS (01:00–06:55) | ~5,47 | ✅ schedules creados 17:31 |
| 3 | Imagen y task definition a ARM64 | ~1,44 | ⏸️ preparado, **sin aplicar** |

Ahorro aplicado: **~25,7 USD/mes sin impuestos**, de 74,53 a ~48,8 (~56,6 con impuestos).
Alrededor de un tercio.

El cambio 2 se hizo sin corte: `deploymentMinimumHealthyPercent` es 100, así que ECS
levantó la task de Spot y solo entonces drenó la anterior. El cambio 1 tardó 30 segundos en
`modifying` y la aplicación siguió respondiendo 200 durante toda la operación.

**El cambio 3 se descartó a propósito.** Con Spot ya aplicado su ahorro cae de ~4 a ~1,44
USD/mes —el 20% de Graviton se calcula sobre una base ya descontada un 70%— y a cambio
arrastra una trampa de orden: una imagen x86 sobre `runtimePlatform: ARM64`, o al revés, no
arranca (`exec format error`). Diecisiete dólares al año no compensan meter QEMU en el
pipeline. **El repositorio quedó íntegramente en x86**: se escribieron los cambios del
`Dockerfile` y del workflow y se revirtieron, y el bloque `runtime_platform` está comentado
en `ecs.tf`. El procedimiento completo se conserva en el runbook por si algún día compensa.

### Lo que sigue sin resolver

Tras estos cambios, la factura estimada queda en ~48,8 USD/mes, de los cuales **ALB (17,58)
más IPv4 (13,87) son ~65%**. Ninguno de los cuatro cambios los toca, y el `LCUUsage` de
0,0018 USD demuestra que ese gasto no responde a la demanda. Eliminarlos exige sacar la API
de Fargate; ver el cierre de `runbook-ahorro-costos.md`.

## Diagrama general

```mermaid
flowchart TB
    subgraph dev["Entrega"]
        GH["GitHub · repo bible-references<br/>push a main"]
        GHA["GitHub Actions · aws-dep.yml<br/>JDK 25 + Maven → docker build"]
        GH --> GHA
    end

    USER(["Navegador"])

    subgraph aws["AWS · cuenta 274869222183"]
        CF["CloudFront E1MTT1XP2UUMYU<br/>escrituraclave.com · www<br/>redirect-to-https<br/>inyecta X-Origin-Verify"]
        S3[("S3 biblia-frontend-prod<br/>bundle estático · acceso público bloqueado")]

        ECR["Amazon ECR<br/>bible-references :latest / :sha"]

        subgraph vpc["VPC vpc-0a224eceeb1c0720a · mx-central-1"]
            subgraph pub["Subredes PÚBLICAS · rtb-0b06ff11bb534e762 → IGW"]
                ALB["ALB bible-alb · internet-facing<br/>listener HTTP :80<br/>regla 1: X-Origin-Verify → forward<br/>default: 403<br/>SG: solo prefix list CloudFront"]

                subgraph ecs["ECS cluster biblia-cluster"]
                    TASK["Task bible-references-task:21 · FARGATE_SPOT<br/>1/1 running · 512 CPU / 1024 MB<br/>:8080 · sg-09f1e2be72d76adbe<br/>health check HEALTHY<br/>apagada 01:00-06:55"]
                end

                RDS[("RDS database-2 · PostgreSQL 17.9<br/>db.t4g.micro · single-AZ<br/>PubliclyAccessible FALSE<br/>bible-rds-sg: 5432 desde bible-ecs-sg<br/>parada 01:10-06:40")]
            end
        end

        SM["Secrets Manager<br/>bible-references/db"]
        CW["CloudWatch Logs<br/>/ecs/bible-references"]
        IAM["IAM ecsTaskExecutionRole<br/>executionRole + taskRole"]
        SCH["EventBridge Scheduler<br/>4 schedules · America/Mexico_City<br/>apagado nocturno"]
    end

    INTERNET(["Cualquier host<br/>de internet"])

    GHA -->|"docker push"| ECR
    GHA -->|"update service"| TASK
    ECR -.->|"pull imagen"| TASK

    USER -->|"HTTPS"| CF
    CF -->|"default behavior · OAC"| S3
    CF -->|"behavior /api/* · http-only<br/>con header secreto"| ALB
    ALB -->|"HTTP :8080 · tg bible-tg<br/>health /actuator/health"| TASK
    TASK -->|"JDBC 5432"| RDS

    INTERNET -.->|"bloqueado: sin IP publica<br/>hallazgo 1 resuelto"| RDS
    INTERNET -.->|"bloqueado: SG + header"| ALB

    TASK -.->|"secretos al arrancar"| SM
    TASK -.->|"awslogs"| CW
    IAM -.-> TASK

    SCH -.->|"desiredCount 0 / 1"| TASK
    SCH -.->|"stop / start"| RDS

    classDef awsSvc fill:#232f3e,stroke:#ff9900,color:#fff
    classDef ext fill:#1a4d5c,stroke:#4dd0e1,color:#fff
    classDef risk fill:#5c1a1a,stroke:#e57373,color:#fff
    class ECR,TASK,SM,CW,IAM,CF,S3,ALB,RDS,SCH awsSvc
    class GH,GHA,USER ext
    class INTERNET risk
```

## Camino real del tráfico

CloudFront **sí fronteda la API**. La distribución `E1MTT1XP2UUMYU` tiene dos orígenes:

| Behavior | Origen | Destino | Protocolo al origen |
|---|---|---|---|
| default | `biblia-frontend-prod.s3...` | S3 (bundle estático) | OAC, sin acceso público |
| `/api/*` | `Backend-API` | `bible-alb-1227546912...elb.amazonaws.com` | `http-only` + header `X-Origin-Verify` |

Consecuencias:

- El navegador habla **siempre HTTPS con CloudFront**, y el edge termina TLS. Por eso el
  ALB no necesita listener 443 y solo tiene HTTP:80.
- El frontend y la API comparten origen (`escrituraclave.com`), así que **el CORS no
  interviene en el camino de producción**. La configuración de `app.frontend.origin` y el
  `OriginValidationFilter` siguen siendo útiles para desarrollo local y como defensa en
  profundidad, pero no son lo que habilita el tráfico real.
- Desde el 2026-07-31, el ALB solo acepta conexiones desde los rangos de CloudFront **y**
  solo atiende peticiones que traigan el header secreto. Todo lo demás recibe 403.

## Flujo de despliegue (CI/CD)

```mermaid
sequenceDiagram
    autonumber
    participant Dev as Desarrollador
    participant GHA as GitHub Actions
    participant ECR as Amazon ECR
    participant ECS as Amazon ECS
    participant ALB as ALB bible-alb

    Dev->>GHA: push a main
    GHA->>GHA: setup JDK 25 (temurin) + mvn clean package -DskipTests
    GHA->>GHA: configure-aws-credentials (secrets de GitHub)
    GHA->>ECR: amazon-ecr-login + docker push :sha y :latest
    GHA->>ECS: describe-task-definition bible-references-task
    GHA->>GHA: render-task-definition (sustituye image por :sha)
    GHA->>ECS: deploy-task-definition al service bible-references-service
    ECS->>ECR: pull de la nueva imagen
    ECS->>ALB: registra la IP de la task en el target group bible-tg
    ALB-->>ECS: health check HTTP /actuator/health (30 s, umbral 2)
    ECS-->>GHA: servicio estable (wait-for-service-stability)
```

> **El `task-definition.json` del repositorio no se despliega.** El workflow lo sobrescribe
> con `aws ecs describe-task-definition` antes de renderizar, así que editarlo no tiene
> efecto: los cambios de CPU, memoria o health check hay que registrarlos como revisión
> nueva en AWS. Invertir esa relación —que el repo sea la fuente de verdad— está pendiente
> como mejora de pipeline.

## Componentes verificados

| Componente | Identificador | Detalle confirmado |
|---|---|---|
| CDN | CloudFront `E1MTT1XP2UUMYU` | Alias `escrituraclave.com` y `www`; `redirect-to-https`; inyecta `X-Origin-Verify` en el origen `Backend-API` |
| Frontend estático | S3 `biblia-frontend-prod` | Los 4 flags de Block Public Access en `true`; policy no pública |
| Registro de imágenes | ECR `bible-references` | Tags `:latest` y `:<git-sha>`; lifecycle policy que conserva las 2 más recientes (desde 2026-08-04) |
| Cluster | `biblia-cluster` | ECS |
| Servicio | `bible-references-service` | `ACTIVE`, desired 1 / running 1, capacity provider `FARGATE_SPOT` (desde 2026-08-13) |
| Task definition | `bible-references-task:21` | 512 CPU / 1024 MB, `awsvpc`, health check `curl -f .../actuator/health` con `startPeriod: 180` |
| Red de la task | subredes `...1526d` (1a) y `...87fcd` (1b) | `assignPublicIp: ENABLED`, SG `sg-09f1e2be72d76adbe` |
| Load balancer | `bible-alb` | Internet-facing; un solo listener: HTTP:80 |
| Reglas del listener | prioridad 1 / default | 1: header `X-Origin-Verify` → `forward` a `bible-tg`; default: `fixed-response` 403 |
| Target group | `bible-tg` | HTTP 8080, target type `ip`, health check `/actuator/health`, intervalo 30 s, umbral 2 |
| SG del ALB | `sg-0f48e25801a170bef` (`bible-alb-sg`) | Única regla de entrada: `tcp/80` desde `pl-0246509e78ddf0729` |
| SG de las tasks | `sg-09f1e2be72d76adbe` (`bible-ecs-sg`) | Ingress 8080 **solo desde `bible-alb-sg`** — correcto |
| Base de datos | RDS `database-2` | PostgreSQL 17.9, `db.t4g.micro`, single-AZ, cifrada, backup 1 día, **sin IP pública** (desde 2026-08-13) |
| SG de la base de datos | `sg-05d935e48e7d6fb1f` (`bible-rds-sg`) | Ingress `5432` **solo desde `bible-ecs-sg`** (desde 2026-08-13) |
| Apagado nocturno | 4 schedules de EventBridge Scheduler | `bible-ecs-stop` 01:00, `bible-rds-stop` 01:10, `bible-rds-start` 06:40, `bible-ecs-start` 06:55 (`America/Mexico_City`); rol `bible-scheduler-role` |
| Subredes y rutas | `rtb-0b06ff11bb534e762` (main) | Única tabla de rutas: `0.0.0.0/0 → igw-01d6dea5b230b8bdb`; **todas las subredes son públicas** |
| Secretos | `bible-references/db` | URL apunta a `database-2...rds.amazonaws.com:5432/bible_db` |
| Logs | `/ecs/bible-references` | Driver `awslogs`, prefijo `ecs`; retención 14 días (desde 2026-08-04). Único log group de la cuenta |
| IAM | `ecsTaskExecutionRole` | Usado como execution role **y** como task role |

## Hallazgos

Estado al 2026-08-04. Los hallazgos 9 a 12 salieron del barrido de objetos sin uso de ese
día y no existían en la revisión del 2026-07-31.

| # | Estado | Hallazgo |
|---|---|---|
| 0 | ✅ RESUELTO | Bucle de reinicio: `curl` ausente en la imagen |
| 1 | ✅ RESUELTO | La base de datos aceptaba 5432 desde `0.0.0.0/0` — cerrado el 2026-08-13 |
| 2 | ✅ RESUELTO | El ALB era alcanzable saltándose CloudFront |
| 3 | 🟡 ABIERTO — medio | RDS single-AZ, backup 1 día, sin deletion protection |
| 4 | 🔵 RESUELTO EN CÓDIGO | Hazelcast con multicast, no funciona en VPC — sustituido por Caffeine el 2026-08-13, **pendiente de desplegar** |
| 5 | 🟡 ABIERTO — medio | `taskRole` = `executionRole`, con `SecretsManagerReadWrite` |
| 6 | 🟡 ABIERTO — medio | Llaves estáticas en GitHub Actions en vez de OIDC |
| 6b | ❔ POR CONFIRMAR | Rechazos de `OriginValidationFilter` sobre rutas legítimas |
| 7 | 🔵 PARCIAL | Recursos huérfanos — solo queda el bucket, exige root |
| 8 | 🔵 PARCIAL | Infraestructura versionada en `terraform/` pero **sin adoptar** |
| 9 | 🟡 ABIERTO — medio | Llaves de acceso de larga vida, una sin usar desde 2025 |
| 10 | ❔ POR REVISAR | Una cuenta de AWS **ajena** tiene acceso concedido a un bucket |
| 11 | ℹ️ INFORMATIVO | El cluster de ECS lo gestiona CloudFormation, no la consola |
| 12 | ✅ RESUELTO | ECR sin caducidad de imágenes y logs sin retención |

Prioridad real: **cerrado el 1, ya no queda ningún crítico abierto.** El 8 pasa a encabezar
la lista, no por gravedad propia sino porque adoptar el state es lo que permite corregir los
demás por código en vez de a mano.

### 0. RESUELTO — Bucle de reinicio del servicio

ECS reemplazaba la task continuamente. Los tasks morían con `healthStatus: UNHEALTHY` y
`exitCode: 137`.

**Causa: `curl` no existe en la imagen del contenedor.** El health check de la task
definition era:

```
CMD-SHELL   curl -f http://localhost:8080/actuator/health || exit 1
```

`eclipse-temurin:25-jre-jammy` es una imagen JRE mínima que no trae `curl` ni `wget`.
Verificado ejecutando la imagen real de ECR:

```
$ docker run --rm --entrypoint sh <ecr>/bible-references:latest -c "command -v curl"
CURL NO EXISTE
```

El comando fallaba **siempre**, con independencia del estado de la aplicación. La app
arrancaba correctamente y sin errores, en ~64 s (`Started BibleReferencesApplication in
64.016 seconds`). El health check del **ALB** —que sí hace un GET HTTP real sobre el mismo
endpoint— reportaba el target `healthy`. Esa asimetría entre un chequeo externo que pasa y
uno interno que falla era la firma del problema.

Métricas que descartaron otras causas: memoria estable entre 26 % y 33 % (no es OOM), CPU
en ~1,5 % fuera de los picos de arranque, y sin excepciones en el log.

**Secuencia de corrección:**

| Rev | Cambio | Resultado |
|---|---|---|
| 18 | `startPeriod` 60 → 180, `timeout` 10 → 15 | **Fallido** — el comando nunca podía tener éxito |
| 19 | Health check de contenedor eliminado | Bucle detenido; task en `UNKNOWN` |
| 20 | Despliegue de CI con la imagen que instala `curl` | `curl 7.81.0` confirmado en producción |
| 21 | Health check restaurado (`startPeriod: 180`, `timeout: 15`) | **`healthStatus: HEALTHY`** |

El `Dockerfile` versionado instala `curl` en la etapa de runtime, así que las imágenes
futuras lo conservarán. El último fallo de health check en el historial del servicio es de
las 21:26:47, previo a la revisión 19; no ha habido ninguno después.

> **Intento fallido registrado para no repetirlo.** Se atribuyó primero el fallo a que el
> `startPeriod` de 60 s quedaba 4 s por debajo del arranque de 64 s, y se registró la
> revisión 18 con `startPeriod: 180` y `timeout: 15`. No sirvió: el comando nunca podía
> tener éxito. El razonamiento que descartó la hipótesis de `curl` —"si faltara, ningún
> task pasaría de ~150 s"— era erróneo, porque ECS no reemplaza el contenedor en cuanto lo
> marca `UNHEALTHY`, sino con una latencia observada de entre 8 y 20 minutos.

Herramientas disponibles en la imagen, comprobadas: `bash`, `perl`, `getent`, `timeout`,
`java`. Ausentes: `curl`, `wget`, `nc`, `python3`, `busybox`.

### 1. RESUELTO — La base de datos estaba expuesta a internet

Hasta el 2026-08-13, tres condiciones se combinaban:

- `database-2` tenía `PubliclyAccessible: true`.
- Vive en el subnet group `default-vpc-0a224eceeb1c0720a`, cuyas tres subredes usan la
  tabla de rutas principal con `0.0.0.0/0 → igw-01d6dea5b230b8bdb`, es decir, son públicas.
- Su grupo de seguridad era el `default` de la VPC, que permitía **tcp/5432 desde
  `0.0.0.0/0`**.

`database-2.c1ggwasugrmo.mx-central-1.rds.amazonaws.com:5432` aceptaba conexiones desde
cualquier host de internet, y lo único que separaba los datos de un atacante era la
contraseña.

**Corregido el 2026-08-13**, en el mismo trabajo de reducción de costes: quitar la IP
pública cerraba el hallazgo *y* eliminaba una dirección IPv4 facturable, así que la misma
acción servía para las dos cosas.

| Paso | Acción | Resultado |
|---|---|---|
| 1 | Creado `bible-rds-sg` (`sg-05d935e48e7d6fb1f`) con `5432` solo desde `bible-ecs-sg` | Mismo patrón que ya usaba el SG de las tasks |
| 2 | `modify-db-instance --vpc-security-group-ids ... --no-publicly-accessible` | 30 s en `modifying`; sin corte perceptible |
| 3 | Revocada la regla `5432 desde 0.0.0.0/0` del SG `default` | El grupo queda solo con su regla `self` |

Verificado después: `PubliclyAccessible: false`, un único SG asociado, 0 interfaces de red
usando el grupo `default`, y `/api/bible/chapter/{1/1, 1/7, 43/3}` respondiendo 200 —o sea
que las consultas a la base siguen funcionando.

**Queda pendiente**, como defensa en profundidad y sin urgencia:

- La instancia sigue en subredes públicas (condición 2). Sin IP pública ni regla abierta ya
  no es alcanzable, pero lo limpio sería un DB subnet group privado propio.
- Rotar la contraseña, asumiendo que pudo haber sido expuesta durante el tiempo que el
  puerto estuvo abierto.

> **Consecuencia operativa:** la base de datos ya no es accesible desde fuera de la VPC.
> Para administrarla con un cliente SQL hace falta port forwarding por SSM o un bastion.

### 2. RESUELTO — El ALB era alcanzable saltándose CloudFront

`bible-alb-sg` admitía 80 y 443 desde `0.0.0.0/0`, de modo que cualquiera podía pegarle
directamente al DNS del balanceador en HTTP plano, evitando el edge y todo lo que
CloudFront aporta.

**Aplicado en dos fases:**

*Fase 1 (red).* El SG del ALB quedó con una única regla de entrada: `tcp/80` desde la
managed prefix list `pl-0246509e78ddf0729`
(`com.amazonaws.global.cloudfront.origin-facing`). Se revocaron las reglas `80` y `443`
desde `0.0.0.0/0`; la de 443 no tenía listener detrás.

*Fase 2 (identidad del origen).* La fase 1 deja fuera a cualquier host que no sea
CloudFront, pero no distingue esta distribución de la de otra cuenta. CloudFront inyecta
ahora `X-Origin-Verify` con un valor secreto de 32 bytes, y el listener del ALB lo exige:

| Prioridad | Condición | Acción |
|---|---|---|
| 1 | header `X-Origin-Verify` con el valor secreto | `forward` a `bible-tg` |
| default | — | `fixed-response` 403 |

Verificado tras el cambio: el sitio y `/api/bible/chapter/1/1` responden 200, el acceso
directo al DNS del ALB agota el tiempo de espera, y el target sigue `healthy`.

El secreto vive únicamente en la configuración de CloudFront; no se guardó copia local.
Para recuperarlo:

```bash
aws cloudfront get-distribution-config --id E1MTT1XP2UUMYU \
  --query 'DistributionConfig.Origins.Items[?Id==`Backend-API`].CustomHeaders.Items' \
  --output json
```

> **Lo que esto no cubre:** el tramo CloudFront → ALB sigue siendo `http-only`, sin
> cifrar. Cerrarlo requiere un listener 443 en el ALB con certificado ACM y cambiar el
> origen a `https-only`. Queda pendiente.

### 3. MEDIO — Sin resiliencia en la capa de datos ni en la de cómputo

`database-2` es single-AZ, con `BackupRetentionPeriod: 1` día y
`DeletionProtection: false`. El servicio ECS corre con `desiredCount: 1`. Cualquier fallo
de zona, o un `delete-db-instance` accidental, implica caída e incluso pérdida de datos.
Como mínimo: activar deletion protection, subir la retención de backups y considerar
Multi-AZ. Subir el desired count a 2 exige antes resolver el punto 4.

### 4. RESUELTO EN CÓDIGO — Hazelcast usaba descubrimiento por multicast

`hazelcast.yaml` declaraba `multicast: enabled: true`, y el multicast no funciona dentro de
una VPC de AWS, Fargate `awsvpc` incluido. Con una sola task nada fallaba, pero al escalar
cada instancia habría tenido su propia caché aislada sirviendo datos divergentes: toda la
complejidad de una caché distribuida sin ninguna de sus ventajas.

**Sustituido por Caffeine el 2026-08-13.** Al inspeccionar el uso real resultó que Hazelcast
se usaba **únicamente como `CacheManager` detrás de `@Cacheable`** —sin `IMap`, sin locks ni
pub/sub—, así que fue un reemplazo directo: las 9 anotaciones `@Cacheable` de los
repositorios no se tocaron.

| Cambio | Detalle |
|---|---|
| `pom.xml` | Fuera `hazelcast` y `hazelcast-spring` 5.5.0; dentro `caffeine` (versión del BOM) |
| Borrados | `config/HazelcastConfig.java` y `resources/hazelcast.yaml` |
| `application.yaml` | `spring.cache.type: caffeine` + `spec: maximumSize=500,expireAfterWrite=24h` |

El `expireAfterWrite=24h` conserva el TTL que `hazelcast.yaml` aplicaba al mapa `verses`.
El `maximumSize` es nuevo: Hazelcast no imponía ningún límite y la caché vive en la memoria
del proceso. En la práctica no se alcanzará, porque lo que entra en caché ya lo restringen
`VerseCacheCondition` y `WordCacheCondition` desde las condiciones SpEL.

Efecto colateral útil para la migración a Lambda: **el JAR pasó de 75 MB a 59 MB**, un 21%
menos.

> **Se hizo primero porque es el único trabajo de C2 que vale por sí solo:** se despliega en
> la infraestructura actual, cierra este hallazgo y no se tira aunque la migración a Lambda
> se abandone.

**Cubierto por `CacheConfigurationTest`**, que carga el `application.yaml` real mediante
`ConfigDataApplicationContextInitializer`. Existía un vacío real: `BibleReferencesApplicationTests`
tiene su `contextLoads` **comentado**, así que el contexto de Spring nunca se levantaba en la
suite, y las pruebas de servicio son unitarias con Mockito. El build habría pasado en verde
con la caché rota. Conviene descomentar ese `contextLoads` en algún momento, aunque exige
resolver de qué base de datos tira la prueba.

### 5. MEDIO — El task role y el execution role son el mismo

`taskRoleArn` y `executionRoleArn` apuntan ambos a `ecsTaskExecutionRole`, de modo que la
aplicación corre con permisos de pull de ECR y lectura de secretos que no necesita en
runtime. Conviene separarlos y dejar el task role con lo mínimo indispensable.

### 6. MEDIO — Llaves estáticas en GitHub Actions

El workflow se autentica con `AWS_ACCESS_KEY_ID` y `AWS_SECRET_ACCESS_KEY`, credenciales
de larga vida guardadas como secretos del repositorio. OIDC con un rol asumible las
elimina por completo.

### 6b. POR CONFIRMAR — Peticiones rechazadas por el filtro de origen

`OriginValidationFilter` no valida el header estándar `Origin`, sino uno propio,
`X-Client-Origin`, y devuelve 403 cuando falta. En los logs aparecen rechazos sobre rutas
que parecen tráfico legítimo de la aplicación:

```
Request blocked: missing Origin header, path=/api/bible/chapter/1/7
Request blocked: missing Origin header, path=/api/bible/chapter/1/1
Request blocked: missing Origin header, path=/api/bible/chapter/1/8
```

Si el frontend envía `X-Client-Origin`, estos rechazos serían escáneres o bots pegándole
al ALB, y el filtro está haciendo su trabajo. Si no lo envía, son usuarios reales
recibiendo 403. Conviene revisar el cliente antes de decidir si es un problema; el nombre
del header hace fácil confundirlo con el `Origin` que manda el navegador solo.

> Nota: tras la fase 2 del hallazgo 2, los bots ya no pueden alcanzar el ALB
> directamente, así que si estos rechazos eran escáneres deberían desaparecer del log. Es
> una forma sencilla de despejar la duda: si siguen apareciendo, vienen del frontend.

### 7. PARCIALMENTE RESUELTO — Recursos huérfanos

Un barrido completo de la cuenta el 2026-08-04 encontró varios recursos sin uso. Dos se
eliminaron ese mismo día; el resto sigue abierto.

**Eliminados el 2026-08-04**, tras confirmar que nada los referenciaba:

| Recurso | Evidencia previa al borrado |
|---|---|
| Target group `biblia-frontend-tg` | 0 targets, 0 balanceadores asociados; ninguna regla del listener lo referenciaba |
| Log group `/ecs/biblia-frontend-task` | La familia `biblia-frontend-task` tenía 0 revisiones activas; último evento en abril de 2026 |

Ambos eran restos de cuando se pensó servir el frontend desde el balanceador; hoy lo sirve
CloudFront desde S3. Tras el borrado se verificó que `bible-tg` sigue `healthy` y el
servicio en 1/1. `bible-tg` y `/ecs/bible-references` son ahora el único target group y el
único log group de la cuenta.

**Eliminados también el 2026-08-04, en una segunda pasada:**

| Recurso | Cómo |
|---|---|
| SGs `biblia-sg` (`sg-06179292d0aba63da`) y `biblia-alb-sg` (`sg-097d84a75b2c197ef`) | `delete-security-group`, tras confirmar 0 ENIs y 0 referencias desde reglas de otros SGs |
| Roles `ecsTaskExecRole`, `getUsersOpen-role-lobve5u3`, `getUsersSecure-role-s844o17t` | Ya no existían al ir a borrarlos |
| Stacks `sam-app`, `my-new-app`, `pets-service` (us-east-1) | `delete-stack` |
| Stack `aws-sam-cli-managed-default` (us-east-1) | `delete-stack`; su bucket ya no existía |
| Políticas `testgdavpolicy`, `ListAllBuckets-test1` y 2× `AWSLambdaBasicExecutionRole-*` | `delete-policy`, todas con 0 adjuntos |

**Se borraron por stack, no recurso a recurso.** Las 2 Lambdas de `us-east-1` pertenecían a
stacks de CloudFormation de SAM (2024), y con ellas venían recursos que el primer barrido
no había encontrado: **3 API Gateways** (`z50n53vqpk` y `mkm44gg4aj` REST, `gusjzecqk4`
HTTP) con sus stages `Prod`/`$default` desplegados, más los roles IAM y los permisos de
invocación. Borrar los Lambdas sueltos habría dejado los stacks en estado inconsistente y
los API Gateways vivos —endpoints públicos— sin que nadie los echara de menos.

Estado resultante, verificado: **1 rol** (`ecsTaskExecutionRole`), **1 política**
(`biblia-frontend-deploy-policy`), **3 security groups** (todos en uso), y en `us-east-1`
**0** stacks, **0** Lambdas y **0** API Gateways. Producción intacta: servicio 1/1, target
`healthy` y `https://escrituraclave.com/api/bible/chapter/1/1` respondiendo 200.

**Pendiente, requiere credenciales root:**

| Recurso | Bloqueo |
|---|---|
| Bucket `testbucketfordataassessments` (us-east-1, 4,7 KB) | Su propia bucket policy lo impide |

La policy (`AWSConsole-AccessLogs-Policy`, de octubre de 2017) es un `Deny` sobre
`Principal: "*"` que **incluye `s3:DeleteBucketPolicy`**, con excepción únicamente para
principals cuyo ARN contenga `797873946194` —una cuenta de AWS ajena, probablemente de
algún proveedor de auditoría— o para peticiones desde una lista fija de IPs de 2017. El
usuario `gdgr` no cumple ninguna de las dos, así que ni puede vaciar el bucket ni retirar
la policy.

Un `Deny` explícito en una bucket policy no se puede sortear con permisos de IAM. La única
salida es el salvaguarda de AWS contra el bloqueo permanente: **el usuario root de la
cuenta propietaria siempre conserva `s3:DeleteBucketPolicy`.** Entrando como root:

```bash
aws s3api delete-bucket-policy --bucket testbucketfordataassessments
aws s3 rm s3://testbucketfordataassessments --recursive
aws s3api delete-bucket --bucket testbucketfordataassessments --region us-east-1
```

Cuesta céntimos al mes, así que no corre prisa; lo que sí conviene revisar es **por qué una
cuenta ajena tuvo acceso concedido a un bucket de esta cuenta**.

> **Lo que el barrido confirmó que está limpio:** 0 volúmenes EBS sueltos, 0 Elastic IPs
> sin asociar, 0 ENIs disponibles, 0 instancias EC2, 0 snapshots manuales, 0 AMIs, 0 NAT
> gateways, 0 VPC endpoints, 0 hosted zones en Route53. El barrido por **todas** las
> regiones confirma que no hay nada facturable fuera de `mx-central-1`. La alarma de
> facturación funciona y tiene suscripción confirmada.

### 8. PARCIALMENTE RESUELTO — Infraestructura versionada pero no adoptada

Toda la infraestructura descrita aquí está ahora declarada en `terraform/`. El módulo
**describe el estado real, no una versión mejorada de él**: los hallazgos 3, 4 y 5 quedan
escritos tal como están, con comentarios que explican qué falla y cómo se corrige, para que
`plan` no proponga cambios. Verificado el 2026-08-04:

```
Plan: 28 to import, 0 to add, 3 to change, 0 to destroy.
```

Los 3 cambios son cosméticos y no corresponden a diferencias reales con AWS; están
explicados en `terraform/README.md`.

> **Ese recuento ya no vale.** Los cambios del 2026-08-13 añadieron ocho recursos nuevos
> —`bible-rds-sg` y su regla, el rol y la política del scheduler, y los cuatro schedules—,
> cada uno con su bloque en `imports.tf`. **El plan no se ha vuelto a ejecutar**: el
> registry de Terraform no era alcanzable desde el entorno donde se aplicaron los cambios,
> así que `init` falló y no se pudo revalidar. Conviene correrlo antes de dar el módulo por
> bueno.

**Lo que falta: nunca se ha ejecutado `terraform apply`.** No existe archivo de state, así
que hoy el módulo es documentación ejecutable, no la fuente de verdad:

- AWS sigue siendo la única autoridad sobre lo que está desplegado.
- Un cambio hecho a mano en la consola no lo detecta nadie hasta que alguien vuelva a
  correr `plan`.
- Los 28 bloques `import` de `imports.tf` siguen siendo necesarios; solo se pueden borrar
  después del primer `apply`.

Además, `.github/workflows/aws-dep.yml` sobrescribe la task definition en cada push
(`aws ecs describe-task-definition > task-definition.json`), de modo que el pipeline y
Terraform compiten por ese recurso. El módulo lo evita hoy con `ignore_changes`, que es un
parche. Ver el apartado correspondiente de `terraform/README.md`.

Siguiente paso: adoptar el state (`apply` de los imports, que no toca nada en AWS) y
mover el state a un backend S3 cifrado —contiene el header secreto de CloudFront en
claro—. Hay un ejemplo comentado en `terraform/versions.tf`.

### 9. MEDIO — Llaves de acceso de larga vida, algunas sin usar

Complementa el hallazgo 6: además de que GitHub Actions use llaves estáticas, las tres que
existen en la cuenta son credenciales de larga vida sin rotación. Verificado el 2026-08-04
con `get-access-key-last-used`:

| Usuario | Llave creada | Último uso | Comentario |
|---|---|---|---|
| `gdgr` | 2021-08-27 | **2025-07-18** | 5 años de antigüedad y más de uno sin usarse. Es el usuario humano con permisos amplios |
| `github-actions-biblia-frontend` | 2026-04-08 | 2026-04-28 | ~3 meses parada; sugiere que ese pipeline dejó de correr |
| `github-actions-biblia` | 2026-04-07 | 2026-08-01 | En uso activo; es la del workflow de despliegue |

La de `gdgr` es la más relevante: una llave de acceso programático de un usuario humano,
sin usar en más de un año, es superficie de ataque pura. Si no hace falta, desactivarla
(`update-access-key --status Inactive`) y borrarla tras confirmar que nada se rompe. Para
el uso interactivo está `aws login`, que ya se usa en esta cuenta.

La de `github-actions-biblia-frontend` conviene resolverla junto con el hallazgo 6: o el
pipeline vuelve a usarse y migra a OIDC, o el usuario y su llave sobran.

### 10. POR REVISAR — Una cuenta de AWS ajena tiene acceso concedido a un bucket

El bucket `testbucketfordataassessments` (us-east-1, 4,7 KB) tiene una bucket policy de
octubre de 2017 (`AWSConsole-AccessLogs-Policy-1508951545812`) que deniega el acceso a
`Principal: "*"` **salvo** que se cumpla una de dos excepciones:

- que el ARN del principal contenga `797873946194` — **una cuenta de AWS que no es esta**
- o que la petición venga de una lista fija de IPs de 2017

El efecto práctico hoy es doble. Por un lado el propietario legítimo **no puede** vaciar ni
borrar el bucket, porque el `Deny` incluye `s3:DeleteBucketPolicy` y un deny explícito de
bucket policy no se sortea con permisos de IAM. Por otro, una cuenta de terceros conserva
sobre el papel un acceso concedido hace ocho años.

No se ha podido determinar a quién pertenece esa cuenta; el nombre del bucket y el de la
policy apuntan a alguna herramienta de evaluación o auditoría contratada entonces. Antes de
borrarlo conviene entender de dónde salió, porque si hubo más recursos con el mismo patrón
puede que queden otros permisos cruzados sin inventariar.

Para eliminarlo hace falta el usuario **root** de la cuenta, que es el único que conserva
`s3:DeleteBucketPolicy` frente a un deny así (salvaguarda de AWS contra el bloqueo
permanente):

```bash
aws s3api delete-bucket-policy --bucket testbucketfordataassessments
aws s3 rm s3://testbucketfordataassessments --recursive
aws s3api delete-bucket --bucket testbucketfordataassessments --region us-east-1
```

### 11. INFORMATIVO — El cluster de ECS lo gestiona CloudFormation

`biblia-cluster` no se creó a mano: pertenece al stack
`Infra-ECS-Cluster-biblia-cluster-7dad23f7` (mx-central-1), que lo declara como su único
recurso. Se descubrió al inventariar los stacks durante la limpieza del hallazgo 7.

Importa por el hallazgo 8: cuando se adopte el state, Terraform importará un recurso que
**otro gestor ya considera suyo**. No rompe nada de inmediato —el stack no se toca—, pero
deja dos sistemas con autoridad sobre el mismo cluster, igual que ya ocurre entre Terraform
y el pipeline con la task definition. Lo limpio sería eliminar el stack conservando el
cluster (`delete-stack` con `--retain-resources`) antes de adoptar, o dejar el cluster
fuera del módulo.

Matiz importante: **este stack no es un huérfano y no debe borrarse sin más.** Un
`delete-stack` normal se llevaría por delante el cluster de producción.

### 12. RESUELTO — ECR sin caducidad de imágenes y logs sin retención

El repositorio de ECR no tenía lifecycle policy: el pipeline etiqueta cada build con el SHA
del commit y además mueve `latest`, así que acumulaba una imagen por push sin que nada las
expirara. Había llegado a **18 imágenes y 2,87 GB**. Los dos log groups tampoco tenían
retención, de modo que se facturaban para siempre.

Ambos corregidos el 2026-08-04 (ver el registro de cambios): retención de 14 días en los
logs, y una lifecycle policy que conserva las 2 imágenes más recientes. Detalles y matices
—entre ellos que conservar 2 deja un único paso de rollback— en el registro de cambios y en
`terraform/ecr.tf`.

## Lo que sí está bien configurado

- El bucket S3 tiene los cuatro flags de Block Public Access activos y su policy no es
  pública: el bundle se sirve exclusivamente vía CloudFront.
- `bible-ecs-sg` restringe el puerto 8080 a `bible-alb-sg`, sin CIDR abierto. Es
  exactamente el patrón correcto, y contrasta con la regla del SG de la base de datos.
- El almacenamiento de RDS está cifrado.
- El health check del target group coincide con el endpoint que Actuator expone, y
  Actuator no publica más que `health` con `show-details: never`.
- Desde el 2026-07-31, el ALB solo es alcanzable desde CloudFront y solo con el header
  secreto.

## Cómo se verificó

Consultas de solo lectura usadas para levantar el inventario:

```bash
aws sts get-caller-identity
aws elbv2 describe-listeners --load-balancer-arn <bible-alb>
aws elbv2 describe-rules --listener-arn <listener>
aws elbv2 describe-target-groups --names bible-tg
aws elbv2 describe-target-health --target-group-arn <bible-tg>
aws ecs describe-services --cluster biblia-cluster --services bible-references-service
aws ecs describe-task-definition --task-definition bible-references-task
aws ecs describe-tasks --cluster biblia-cluster --tasks <task>
aws rds describe-db-instances
aws ec2 describe-security-groups     --filters Name=vpc-id,Values=vpc-0a224eceeb1c0720a
aws ec2 describe-security-group-rules --filters Name=group-id,Values=<sg>
aws ec2 describe-route-tables        --filters Name=vpc-id,Values=vpc-0a224eceeb1c0720a
aws ec2 describe-network-interfaces  --filters Name=group-id,Values=<sg>
aws ec2 describe-managed-prefix-lists --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing
aws cloudfront list-distributions
aws cloudfront get-distribution-config --id E1MTT1XP2UUMYU
aws s3api get-public-access-block   --bucket biblia-frontend-prod
aws s3api get-bucket-policy-status  --bucket biblia-frontend-prod
aws logs get-log-events --log-group-name /ecs/bible-references --log-stream-name <stream>
aws cloudwatch get-metric-statistics --namespace AWS/ECS --metric-name MemoryUtilization ...
```

Comandos que **sí modificaron** infraestructura, todos documentados en los runbooks con su
reversión:

```bash
aws ec2 authorize-security-group-ingress   # regla con prefix list de CloudFront
aws ec2 revoke-security-group-ingress      # reglas 80 y 443 abiertas
aws ecs register-task-definition           # revisiones 18 y 19
aws ecs update-service                     # despliegue de la revisión 19
aws cloudfront update-distribution         # header X-Origin-Verify
aws elbv2 create-rule                      # regla de prioridad 1
aws elbv2 modify-listener                  # acción por defecto a 403
```

Añadidos el 2026-08-04, todos en el registro de cambios de ese día:

```bash
aws logs put-retention-policy              # 14 días en ambos log groups
aws ecr put-lifecycle-policy               # conservar las 2 imágenes más recientes
aws elbv2 delete-target-group              # biblia-frontend-tg
aws logs delete-log-group                  # /ecs/biblia-frontend-task
aws cloudformation delete-stack            # 4 stacks de SAM en us-east-1
aws ec2 delete-security-group              # biblia-sg, biblia-alb-sg
aws iam delete-policy                      # 4 políticas sin adjuntos
```

Antes de aplicar la lifecycle policy de ECR se usó `aws ecr start-lifecycle-policy-preview`,
que simula la regla sin borrar nada. Es el comando que conviene tener a mano al tocar
políticas de expiración: confirma exactamente qué imágenes sobreviven.

> Sobre el entorno de trabajo: la AWS CLI en Windows falla con
> `'charmap' codec can't encode character` al leer logs con emoji; se soluciona con
> `PYTHONUTF8=1`. Git Bash convierte rutas como `/ecs/bible-references` en rutas de
> Windows; se soluciona con `MSYS_NO_PATHCONV=1`. Y el `curl` de schannel puede fallar con
> `CRYPT_E_REVOCATION_OFFLINE`, que se sortea con `--ssl-no-revoke`.

## Trabajo pendiente, por prioridad

0. **Verificar el primer disparo del apagado nocturno** (madrugada del 2026-08-14). Es lo
   único con fecha. Comandos en `runbook-ahorro-costos.md`, punto 4.
1. **Hallazgo 8** — adoptar el state de Terraform y moverlo a un backend cifrado. Barato
   (el `apply` de los imports no toca AWS) y desbloquea que los demás hallazgos se
   corrijan por código en vez de a mano. Resolver de paso el **hallazgo 11**, para no
   adoptar un cluster que CloudFormation ya considera suyo.
2. **Hallazgo 9** — desactivar la llave de acceso de `gdgr`, sin usar desde julio de 2025.
   Es un comando y no rompe nada mientras `aws login` siga funcionando.
3. **Hallazgo 3** — deletion protection y retención de backups en RDS.
4. **Rotar la contraseña de la base de datos**, asumiendo que pudo quedar expuesta mientras
   el puerto 5432 estuvo abierto a internet (hallazgo 1, ya cerrado).
5. **Cifrar el tramo CloudFront → ALB** (listener 443 con ACM, origen a `https-only`).
6. **Hallazgo 6b** — confirmar si el frontend envía `X-Client-Origin`.
7. **Hallazgos 4, 5, 6** — Hazelcast, separación de roles IAM, OIDC. El 6 se resuelve junto
   con la llave sobrante de `github-actions-biblia-frontend` (hallazgo 9).
8. **Hallazgos 7 y 10** — el bucket `testbucketfordataassessments`, que exige credenciales
   root. Céntimos al mes; lo relevante no es borrarlo sino entender por qué la cuenta
   `797873946194` tenía acceso concedido.
