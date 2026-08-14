# Runbook — Reducción de costes de AWS

Fecha de redacción: 2026-08-13 · Cuenta `274869222183` · Región `mx-central-1`

Motivación: la aplicación tiene pocos usuarios de día y ninguno de noche, pero factura
como si estuviera saturada las 24 horas. Este runbook recoge cuatro cambios, su
procedimiento de aplicación y su reversión.

> **Estos cambios se aplican por CLI, no con `terraform apply`.** El state sigue sin
> adoptarse (hallazgo 8), así que AWS continúa siendo la única autoridad. El módulo de
> `terraform/` se ha actualizado en el mismo commit para seguir siendo un espejo fiel, pero
> no es lo que despliega.

## Estado: aplicado el 2026-08-13

| # | Cambio | Ahorro/mes | Estado |
|---|---|---|---|
| 1 | RDS sin IP pública + SG dedicado | 3,47 | ✅ 17:40 |
| 2 | Fargate Spot | ~16,80 | ✅ 17:33 |
| 3 | Fargate ARM64 (Graviton) | ~1,44 | ⏸️ **sin aplicar** — ver por qué en el punto 3 |
| 4 | Apagado nocturno de ECS y RDS | ~5,47 | ✅ 17:31, primer disparo la madrugada del 14 |

**Pendiente de verificar:** el primer disparo del apagado nocturno. Ver el final del punto 4.

## Punto de partida — de dónde sale la factura

Julio de 2026, **cifras reales de Cost Explorer** desglosadas por `USAGE_TYPE`, antes de
impuestos:

| Concepto | USD/mes | ¿Apagable de noche? |
|---|---|---|
| `Fargate-vCPU-Hours` (19,65) + `GB-Hours` (4,32) | 23,97 | ✅ |
| `LoadBalancerUsage` — ALB, tarifa base | 17,58 | ❌ factura 24/7 sin targets |
| `PublicIPv4:InUseAddress` — **5** direcciones | 17,34 | parcial |
| `InstanceUsage:db.t4g.micro` | 12,65 | ✅ |
| `RDS:GP2-Storage` (20 GB) | 2,42 | ❌ |
| Secrets Manager, ECR, S3 | 0,58 | ❌ |
| **Total sin impuestos** | **74,53** | |
| Impuestos | 11,92 | |
| **Total** | **86,45** | |

Dos cosas que solo se ven al desglosar por tipo de uso:

- **`LCUUsage` = 0,0018 USD/mes.** El balanceador no procesa prácticamente nada; los 17,58
  USD son tarifa base. Cuesta lo mismo servir un puñado de peticiones que ninguna.
- **Las IPv4 públicas son cinco, no cuatro.** El ALB ocupa **tres** subredes
  (`subnet-02c771c1f18b2308c`, `...1526d`, `...87fcd`), más la de la task de Fargate
  (`assignPublicIp: ENABLED`) y la de RDS. A 0,005 USD/h salen 17,34 USD/mes: casi tanto
  como el ALB, y es la partida que más fácil pasa desapercibida.

Sumadas, **ALB e IPv4 eran el 47% de la factura**, en fontanería de red para una aplicación
sin tráfico medible.

> **Lo que NO hay que hacer: añadir un NAT gateway.** Quitarle la IP pública a la task de
> Fargate ahorraría 3,65 USD/mes, pero la task la necesita para bajar la imagen de ECR y
> leer el secreto —no hay NAT en esta VPC—. Sustituirla por un NAT gateway cuesta ~32
> USD/mes, y por VPC endpoints de interfaz ~7,3 USD/mes cada uno (harían falta cuatro).
> Las dos alternativas cuestan bastante más que lo que ahorran. **La IP pública de la task
> se queda.**

## Cómo se compone el ahorro

Los ahorros **no se suman linealmente**: el 3 y el 4 se aplican sobre una base que el 2 ya
ha reducido.

| Partida | Antes | Después | Cómo |
|---|---|---|---|
| Fargate | 23,97 | ~4,31 | Spot (−70%) → 7,19; noche (−25% de horas) → 5,39; ARM64 pendiente |
| RDS instancia | 12,65 | 9,49 | Noche (−25% de horas) |
| IPv4 públicas | 17,34 | ~13,87 | −1 dirección (RDS); la de la task solo factura mientras corre |
| ALB | 17,58 | 17,58 | **sin cambio** |
| RDS storage | 2,42 | 2,42 | sin cambio |
| Resto | 0,58 | 0,58 | sin cambio |
| **Total sin impuestos** | **74,53** | **~48,8** | **−25,7 (−34%)** |

Con impuestos, de ~86,45 a ~56,6 USD/mes.

> **Y aquí está lo relevante para el siguiente paso:** de los ~48,8 USD que quedan, **ALB
> (17,58) más IPv4 (13,87) son el 65%**. Ninguno de estos cuatro cambios los toca, y el
> `LCUUsage` de 0,0018 USD demuestra que ese gasto no responde a la demanda. Ver el cierre
> del documento.

---

## Verificaciones previas — resueltas el 2026-08-13

| Incógnita | Resultado |
|---|---|
| ¿Existe Fargate Spot en `mx-central-1`? | ✅ **Sí.** `put-cluster-capacity-providers` aceptó `FARGATE_SPOT` |
| ¿Se puede pasar de `launchType` a `capacityProviderStrategy` sin recrear el servicio? | ✅ **Sí.** `update-service` lo aceptó; `launchType` quedó en `null` |
| ¿El universal target de RDS espera `DbInstanceIdentifier` o `DBInstanceIdentifier`? | ❔ **Sin confirmar.** Se creó con `DbInstanceIdentifier`; lo dirá el primer disparo |

Sobre la tercera: **el modo de fallo es benigno.** Los dos schedules de RDS usan la misma
grafía, así que o funcionan ambos o falla ambos. Si fallan, la instancia sencillamente no se
para —se pierden 3,16 USD/mes y no se rompe nada—. El caso peligroso (que pare pero no
arranque) no puede darse.

---

## 1 · RDS sin IP pública y con grupo de seguridad propio ✅

> **Aplicado el 2026-08-13 a las 17:40.** SG creado: `bible-rds-sg` =
> `sg-05d935e48e7d6fb1f`, regla `sgr-0fa7c35fc6741c295`. La instancia estuvo 30 segundos en
> `modifying` y la aplicación siguió respondiendo 200 durante toda la operación: el corte
> previsto no llegó a materializarse. Se aplicó a las 17:40 en horario diurno, con el
> corte aceptado explícitamente.

Cierra el **hallazgo 1**, que llevaba abierto desde el 2026-07-31 y era el único crítico, y
de paso elimina una IPv4 facturable. Es el mejor cambio de la lista: seguridad y coste con
la misma acción.

**Conviene aplicarlo en ventana nocturna.** `--no-publicly-accessible` cambia la
configuración de red de la instancia y puede tumbar las conexiones abiertas.

```bash
# 1.1 · Grupo dedicado, para dejar de usar el `default` de la VPC
RDS_SG=$(aws ec2 create-security-group \
  --group-name bible-rds-sg \
  --description "Security group for Bible RDS instance" \
  --vpc-id vpc-0a224eceeb1c0720a \
  --query GroupId --output text)
echo "Nuevo SG: $RDS_SG"   # anotar: hace falta para revertir

# 1.2 · 5432 únicamente desde las tasks de ECS, sin CIDR abierto
aws ec2 authorize-security-group-ingress \
  --group-id "$RDS_SG" \
  --ip-permissions 'IpProtocol=tcp,FromPort=5432,ToPort=5432,UserIdGroupPairs=[{GroupId=sg-09f1e2be72d76adbe,Description="PostgreSQL solo desde las tasks de ECS"}]'

# 1.3 · Atar la instancia al grupo nuevo y quitarle la IP pública, en una sola operación
aws rds modify-db-instance \
  --db-instance-identifier database-2 \
  --vpc-security-group-ids "$RDS_SG" \
  --no-publicly-accessible \
  --apply-immediately

# 1.4 · Esperar a que termine
aws rds wait db-instance-available --db-instance-identifier database-2

# 1.5 · Solo entonces, revocar la regla abierta del grupo `default`
aws ec2 revoke-security-group-ingress \
  --group-id sg-050bc646783252d10 \
  --protocol tcp --port 5432 --cidr 0.0.0.0/0
```

**Verificación:**

```bash
aws rds describe-db-instances --db-instance-identifier database-2 \
  --query 'DBInstances[0].{Publica:PubliclyAccessible,Grupos:VpcSecurityGroups[].VpcSecurityGroupId}'
# Esperado: Publica=false, y un único grupo, el nuevo

curl -s -o /dev/null -w '%{http_code}\n' https://escrituraclave.com/api/bible/chapter/1/1
# Esperado: 200
```

**Reversión:**

```bash
aws rds modify-db-instance --db-instance-identifier database-2 \
  --vpc-security-group-ids sg-050bc646783252d10 \
  --publicly-accessible --apply-immediately
aws ec2 authorize-security-group-ingress --group-id sg-050bc646783252d10 \
  --protocol tcp --port 5432 --cidr 0.0.0.0/0
```

> **Consecuencia que conviene tener presente:** a partir de aquí la base de datos deja de
> ser accesible desde un portátil fuera de la VPC. Para administrarla hará falta port
> forwarding por SSM o un bastion. Si hoy se conecta a ella con un cliente SQL desde su
> máquina, **eso dejará de funcionar** — es el precio de cerrar el hallazgo 1, y es el
> comportamiento correcto.

---

## 2 · Fargate Spot ✅

> **Aplicado el 2026-08-13 a las 17:33, sin corte.** Como
> `deploymentMinimumHealthyPercent` es 100 y `deploymentMaximumPercent` 200, ECS levantó la
> task de Spot, esperó a que el target estuviera `healthy` y solo entonces drenó la
> anterior. Es el ahorro más grande de los cuatro: **~16,80 USD/mes**.

Hasta un 70% de descuento. A cambio, AWS puede reclamar la capacidad avisando con 2
minutos; con una sola task y un arranque de ~64 s, cada interrupción son unos 2 minutos de
503. Con el perfil de tráfico actual es una compensación razonable.

```bash
# 2.1 · Habilitar el capacity provider en el cluster (se dejan los dos)
aws ecs put-cluster-capacity-providers \
  --cluster biblia-cluster \
  --capacity-providers FARGATE FARGATE_SPOT \
  --default-capacity-provider-strategy capacityProvider=FARGATE_SPOT,weight=1

# 2.2 · Mover el servicio a Spot
aws ecs update-service \
  --cluster biblia-cluster \
  --service bible-references-service \
  --capacity-provider-strategy capacityProvider=FARGATE_SPOT,weight=1,base=0 \
  --force-new-deployment
```

> **Si el paso 2.2 falla**, es porque el servicio se creó con `launchType` y AWS no siempre
> permite convertirlo a `capacityProviderStrategy` sobre la marcha. En ese caso hay que
> recrear el servicio, lo que con `desiredCount: 1` implica un corte. Hágalo en la ventana
> nocturna, cuando ECS ya está a 0 tasks y el corte es invisible.

**Verificación:**

```bash
aws ecs describe-services --cluster biblia-cluster --services bible-references-service \
  --query 'services[0].{Estrategia:capacityProviderStrategy,Corriendo:runningCount}'
aws elbv2 describe-target-health --target-group-arn <bible-tg>   # esperado: healthy
```

**Reversión:** el mismo `update-service` con `capacityProvider=FARGATE`.

---

## 3 · Fargate sobre ARM64 (Graviton) ⏸️ SIN APLICAR

> **Descartado el 2026-08-13, tras ver las cifras reales.** Con Fargate Spot ya aplicado,
> el ahorro adicional cae de ~4 a **~1,44 USD/mes**: el 20% de Graviton se calcula sobre
> una base que Spot ya descontó un 70%. Diecisiete dólares al año no compensan meter QEMU
> en el pipeline ni convivir con la trampa de orden que se describe abajo.
>
> **El repositorio quedó íntegramente en x86.** Se llegaron a escribir los cambios del
> `Dockerfile` (cross-compile con `--platform=$BUILDPLATFORM`) y del workflow (QEMU +
> `buildx --platform linux/arm64`), y **se revirtieron** para no dejar el pipeline
> construyendo una arquitectura que la task definition no declara. El bloque
> `runtime_platform` está comentado en `terraform/ecs.tf`.
>
> Este apartado se conserva como procedimiento completo por si algún día compensa —por
> ejemplo, si se sube la CPU de la task y la base sobre la que aplica el 20% crece—. Para
> ejecutarlo hay que rehacer los tres cambios: `Dockerfile`, workflow y revisión.

~20% más barato a igual CPU y memoria, y Java suele rendir algo mejor en Graviton.

> ### ⚠️ El orden es crítico y no perdona
>
> Una imagen x86 sobre `runtimePlatform: ARM64` —o al revés— falla al arrancar con
> `exec format error`. **La imagen arm64 tiene que estar en ECR ANTES de registrar la
> revisión que declara ARM64**, y ambas cosas deben entrar en la misma revisión.
>
> No basta con hacer merge de los cambios del `Dockerfile` y del workflow: el pipeline hace
> `describe-task-definition` y re-registra, así que produciría una revisión con imagen
> arm64 y plataforma x86 —justo la combinación rota—. Como
> `deploymentMinimumHealthyPercent` es 100, la task antigua seguiría viva y producción no
> se caería, pero el despliegue no estabilizaría nunca y el circuit breaker está
> **desactivado**, así que no revertiría solo.

Procedimiento correcto, construyendo la imagen a mano la primera vez:

```bash
# 3.1 · Construir y subir la imagen arm64 (requiere Docker Desktop con buildx)
ECR=274869222183.dkr.ecr.mx-central-1.amazonaws.com
aws ecr get-login-password --region mx-central-1 \
  | docker login --username AWS --password-stdin $ECR

TAG=$(git rev-parse HEAD)
docker buildx build --platform linux/arm64 \
  --tag $ECR/bible-references:$TAG \
  --tag $ECR/bible-references:latest \
  --provenance false --push .

# 3.2 · Registrar una revisión con la imagen arm64 Y runtimePlatform ARM64
aws ecs describe-task-definition --task-definition bible-references-task \
  --query taskDefinition > /tmp/td.json

python - <<'PY'
import json
td = json.load(open('/tmp/td.json'))
for k in ('taskDefinitionArn','revision','status','requiresAttributes',
          'compatibilities','registeredAt','registeredBy'):
    td.pop(k, None)
td['runtimePlatform'] = {'operatingSystemFamily':'LINUX','cpuArchitecture':'ARM64'}
import os
td['containerDefinitions'][0]['image'] = os.environ['IMAGE']
json.dump(td, open('/tmp/td-arm.json','w'), indent=2)
PY

IMAGE=$ECR/bible-references:$TAG aws ecs register-task-definition \
  --cli-input-json file:///tmp/td-arm.json

# 3.3 · Desplegarla
aws ecs update-service --cluster biblia-cluster \
  --service bible-references-service \
  --task-definition bible-references-task \
  --force-new-deployment
```

A partir de aquí el pipeline arrastra `runtimePlatform` solo, porque lo lee de la revisión
anterior con `describe-task-definition`. Los cambios ya hechos en `Dockerfile` y
`.github/workflows/aws-dep.yml` hacen que todos los builds siguientes sean arm64, de modo
que imagen y plataforma quedan alineadas de forma permanente.

**Verificación:**

```bash
aws ecs describe-task-definition --task-definition bible-references-task \
  --query 'taskDefinition.{Rev:revision,Plataforma:runtimePlatform}'
aws logs tail /ecs/bible-references --since 10m | grep "Started BibleReferencesApplication"
```

Ese último comando sirve además para **volver a medir el arranque**: si en Graviton pasara
de 180 s, habría que subir el `startPeriod` del health check (ver `CLAUDE.md`). No debería:
lo esperable es que baje.

**Reversión:** volver a desplegar la revisión 21, que es x86 y apunta a una imagen x86.

```bash
aws ecs update-service --cluster biblia-cluster \
  --service bible-references-service --task-definition bible-references-task:21
```

> Ojo con la lifecycle policy de ECR, que solo conserva **2 imágenes**. Tras dos
> despliegues arm64, la imagen x86 de la revisión 21 ya no existirá y esta reversión dejará
> de ser posible sin reconstruir desde el commit. Si quiere conservar la salida, suba
> temporalmente el `countNumber` de la política antes de empezar.

---

## 4 · Apagado nocturno de ECS y RDS

Cuatro schedules de EventBridge Scheduler en `America/Mexico_City`:

| Hora local | Acción |
|---|---|
| 01:00 | ECS → 0 tasks |
| 01:10 | `stop-db-instance` |
| 06:40 | `start-db-instance` (tarda 5–10 min) |
| 06:55 | ECS → 1 task (la app arranca en ~64 s) |

**El orden no es negociable.** Si RDS cae con la aplicación viva, HikariCP no puede abrir
conexiones, `/actuator/health` empieza a fallar y el health check mata la task en bucle. Se
retira ECS primero y se levanta el último.

```bash
# 4.1 · Rol para el scheduler
aws iam create-role --role-name bible-scheduler-role \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{
      "Effect":"Allow",
      "Principal":{"Service":"scheduler.amazonaws.com"},
      "Action":"sts:AssumeRole",
      "Condition":{"StringEquals":{"aws:SourceAccount":"274869222183"}}
    }]}'

# 4.2 · Permisos mínimos: escalar ESE servicio y parar/arrancar ESA instancia
SVC_ARN=$(aws ecs describe-services --cluster biblia-cluster \
  --services bible-references-service --query 'services[0].serviceArn' --output text)
DB_ARN=$(aws rds describe-db-instances --db-instance-identifier database-2 \
  --query 'DBInstances[0].DBInstanceArn' --output text)

aws iam put-role-policy --role-name bible-scheduler-role \
  --policy-name bible-scheduler-policy \
  --policy-document "{
    \"Version\":\"2012-10-17\",
    \"Statement\":[
      {\"Effect\":\"Allow\",\"Action\":\"ecs:UpdateService\",\"Resource\":\"$SVC_ARN\"},
      {\"Effect\":\"Allow\",\"Action\":[\"rds:StopDBInstance\",\"rds:StartDBInstance\"],\"Resource\":\"$DB_ARN\"}
    ]}"

ROLE_ARN=arn:aws:iam::274869222183:role/bible-scheduler-role

# 4.3 · Los cuatro schedules
#
# El campo `Input` es una CADENA que contiene JSON, así que va escapado dentro
# del JSON del target. Se escribe a fichero y se pasa con file:// en lugar de
# pelearse con las comillas en la línea de comandos.

crear_schedule () {   # $1 nombre  $2 cron  $3 arn destino  $4 input (json escapado)
  cat > /tmp/target-$1.json <<EOF
{
  "Arn": "$3",
  "RoleArn": "$ROLE_ARN",
  "Input": "$4",
  "RetryPolicy": { "MaximumRetryAttempts": 3 }
}
EOF
  aws scheduler create-schedule --name "$1" \
    --schedule-expression "$2" \
    --schedule-expression-timezone "America/Mexico_City" \
    --flexible-time-window '{"Mode":"OFF"}' \
    --target "file:///tmp/target-$1.json"
}

crear_schedule bible-ecs-stop  "cron(0 1 * * ? *)"  \
  "arn:aws:scheduler:::aws-sdk:ecs:updateService" \
  '{\"Cluster\":\"biblia-cluster\",\"Service\":\"bible-references-service\",\"DesiredCount\":0}'

crear_schedule bible-rds-stop  "cron(10 1 * * ? *)" \
  "arn:aws:scheduler:::aws-sdk:rds:stopDBInstance" \
  '{\"DbInstanceIdentifier\":\"database-2\"}'

crear_schedule bible-rds-start "cron(40 6 * * ? *)" \
  "arn:aws:scheduler:::aws-sdk:rds:startDBInstance" \
  '{\"DbInstanceIdentifier\":\"database-2\"}'

crear_schedule bible-ecs-start "cron(55 6 * * ? *)" \
  "arn:aws:scheduler:::aws-sdk:ecs:updateService" \
  '{\"Cluster\":\"biblia-cluster\",\"Service\":\"bible-references-service\",\"DesiredCount\":1}'
```

> En Git Bash, `file:///tmp/...` puede sufrir la conversión de rutas de MSYS. Si la CLI se
> queja de no encontrar el fichero, anteponga `MSYS_NO_PATHCONV=1` al comando, igual que se
> hace con los nombres de log group (ver la nota de entorno en `aws-architecture.md`).

> **Verificar el nombre del campo de RDS la primera noche.** La API de RDS documenta
> `DBInstanceIdentifier`, pero los universal targets de Scheduler normalizan los nombres al
> estilo del SDK y esperan `DbInstanceIdentifier`. Si el schedule falla silenciosamente,
> es casi seguro esto. No lo dé por bueno hasta comprobarlo:
>
> ```bash
> aws rds describe-db-instances --db-instance-identifier database-2 \
>   --query 'DBInstances[0].DBInstanceStatus'   # a las 01:15 debe decir "stopping" o "stopped"
> ```
>
> Si sigue en `available`, pruebe a recrear los dos schedules de RDS con
> `DBInstanceIdentifier`.

**Verificación general, a la mañana siguiente:**

```bash
aws scheduler list-schedules --query 'Schedules[].{N:Name,Estado:State}'
aws rds describe-db-instances --db-instance-identifier database-2 \
  --query 'DBInstances[0].DBInstanceStatus'           # esperado: available
aws ecs describe-services --cluster biblia-cluster --services bible-references-service \
  --query 'services[0].{Deseado:desiredCount,Corriendo:runningCount}'   # esperado: 1 y 1
curl -s -o /dev/null -w '%{http_code}\n' https://escrituraclave.com/api/bible/chapter/1/1
```

**Reversión** — desactivar sin borrar, que es lo que conviene si algo va mal de madrugada:

```bash
for s in bible-ecs-stop bible-rds-stop bible-rds-start bible-ecs-start; do
  aws scheduler update-schedule --name $s --state DISABLED \
    --schedule-expression "$(aws scheduler get-schedule --name $s --query ScheduleExpression --output text)" \
    --flexible-time-window '{"Mode":"OFF"}' \
    --target "$(aws scheduler get-schedule --name $s --query Target --output json)"
done

# Y dejar todo arriba a mano
aws rds start-db-instance --db-instance-identifier database-2
aws ecs update-service --cluster biblia-cluster \
  --service bible-references-service --desired-count 1
```

### Efecto secundario: 503 de noche

Con 0 tasks registradas, el ALB responde 503 a `/api/*` y CloudFront lo propaga. No afecta
a usuarios reales —de noche no hay—, pero sí a cualquier monitor externo, que empezará a
alertar todas las noches.

El frontend estático de S3 se sigue sirviendo con normalidad: solo cae la API.

Si molesta, se puede añadir una `custom_error_response` en la distribución para devolver
una página de mantenimiento en lugar del 503 crudo. No se ha hecho aquí: tocar la config de
CloudFront obliga a pasar por `update-distribution` con el `X-Origin-Verify` en el payload,
y no compensa el riesgo por un error que nadie va a ver.

---

## Después de aplicar: comprobar el ahorro real

Los números de arriba son estimaciones. Una vez aplicado, confirme el efecto con datos:

```bash
aws ce get-cost-and-usage \
  --time-period Start=2026-07-01,End=2026-09-01 \
  --granularity MONTHLY --metrics UnblendedCost \
  --group-by Type=DIMENSION,Key=SERVICE
```

Conviene además revisar el umbral de la alarma de facturación, que sigue puesto para el
gasto anterior.

## Lo que este runbook deliberadamente no hace

**El ALB sigue costando ~20 USD/mes**, en torno a un tercio de la factura, para servir un
puñado de peticiones al día, y es inmune a todo lo anterior: factura su tarifa base aunque
no haya un solo target registrado.

Eliminarlo exige sacar la API de Fargate y llevarla a Lambda con SnapStart —Function URL
como origen de CloudFront—, lo que además haría innecesarios el cluster de ECS, el header
`X-Origin-Verify` y buena parte del pipeline. Con este volumen de tráfico la factura de
Lambda sería prácticamente cero, y la cuenta bajaría a ~20 USD/mes, casi toda RDS.

El obstáculo es el arranque de 64 s, inaceptable como cold start; SnapStart lo resuelve
restaurando desde un snapshot, pero obliga a sustituir Hazelcast embebido por una caché
local como Caffeine —lo que de paso cerraría el hallazgo 4—. Es un proyecto de uno o dos
días, no un cambio de configuración, y por eso queda fuera de aquí.
