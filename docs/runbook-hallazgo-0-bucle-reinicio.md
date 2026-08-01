# Runbook — Hallazgo 0: bucle de reinicio por health check

> ## Estado: RESUELTO el 2026-07-31 a las 21:30
>
> **Causa real:** `curl` no existe en la imagen `eclipse-temurin:25-jre-jammy`, y el
> health check de contenedor lo invocaba. El comando fallaba siempre.
>
> **Aplicado:**
> - **Revisión 19** — health check de contenedor eliminado. El task pasó de `UNHEALTHY` a
>   `UNKNOWN` (el valor correcto cuando no hay chequeo definido) y el bucle se detuvo. La
>   supervisión queda a cargo del health check del ALB, que hace un GET HTTP real sobre
>   `/actuator/health` y también dispara el reemplazo de tasks caídos.
> - **`Dockerfile`** — se instala `curl` en la etapa de runtime. Verificado con un build
>   local: `curl 7.81.0` presente en la imagen resultante. **Aún no desplegado**: llegará
>   con el próximo push a `main`.
>
> **Pendiente:** una vez que el próximo despliegue publique la imagen con `curl`,
> restaurar el health check de contenedor como revisión 20. Ver la sección final.
>
> **Intento fallido, registrado para no repetirlo:** la revisión 18 subió `startPeriod` de
> 60 a 180 s y `timeout` de 10 a 15 s, sobre la teoría de que los 64 s de arranque
> excedían los 60 s de gracia. No sirvió: el comando nunca podía tener éxito. El
> razonamiento que había descartado la hipótesis de `curl` —"si faltara, ningún task
> pasaría de ~150 s"— era erróneo, porque ECS no reemplaza el contenedor en cuanto lo
> marca `UNHEALTHY`, sino con una latencia observada de 8 a 20 minutos.

---

## Contexto original (diagnóstico inicial, parcialmente incorrecto)

Objetivo: que ECS deje de reemplazar la task cada pocos minutos.

Lo que se midió: la aplicación arranca en **64,0 s** y el `startPeriod` del health check de
contenedor era de **60 s**, con la CPU al 100 % durante todo el arranque en una task de
0,5 vCPU. Ambos datos son ciertos, pero no eran la causa del bucle.

---

## Antes de empezar: el archivo del repo NO es el que se despliega

`.github/workflows/aws-dep.yml` hace esto antes de desplegar:

```yaml
- name: Download current task definition
  run: |
    aws ecs describe-task-definition --task-definition bible-references-task \
      --query taskDefinition > task-definition.json
```

Es decir, **sobrescribe** `task-definition.json` con lo que ya está registrado en AWS y
despliega eso. El archivo versionado en el repo no influye en el despliegue: editarlo no
cambia nada.

Por eso el ajuste hay que **registrar una revisión nueva en AWS**. Una vez registrada, el
pipeline la hereda sola, porque `describe-task-definition` sobre la familia devuelve
siempre la última revisión `ACTIVE`.

---

## Restricción de Fargate que condiciona la decisión

Las combinaciones de CPU y memoria en Fargate son fijas:

| `cpu` | Valores de `memory` admitidos |
|---|---|
| 512 (0,5 vCPU) | 1024 – 4096 |
| 1024 (1 vCPU) | **2048** – 8192 |

La configuración actual es `cpu: 512` / `memory: 1024`. **Subir la CPU a 1024 obliga a
subir la memoria a 2048 como mínimo**, aunque la memoria hoy no sea el problema (se
mantiene en 26–33 %). Eso aproximadamente duplica el costo de la task.

De ahí las dos opciones siguientes.

---

## Opción A — Solo `startPeriod` (recomendada para empezar)

Sin cambio de costo. Ataca directamente la causa medida: los 4 segundos de déficit entre
el arranque real y el período de gracia.

Cambios: `startPeriod` 60 → 180, `timeout` 10 → 15.

## Opción B — `startPeriod` + CPU

Además de lo anterior: `cpu` 512 → 1024 y `memory` 1024 → 2048 (forzado por la tabla de
arriba). Reduce el tiempo de arranque y quita la contención que hace fallar los chequeos,
a cambio de duplicar el costo de cómputo.

Conviene medir con la opción A primero: si el bucle se detiene y el arranque baja de los
180 s de gracia, la opción B pasa a ser una mejora de rendimiento, no una corrección.

---

## Procedimiento

### 1 · Generar la revisión nueva

El script parte de la revisión viva y solo toca los campos indicados. Elimina además los
campos de solo lectura que `register-task-definition` rechaza.

```bash
export AWS_PAGER=""
export PYTHONUTF8=1        # evita el error de encoding del CLI en Windows
D="$USERPROFILE/AppData/Local/Temp"

aws ecs describe-task-definition --task-definition bible-references-task \
  --query taskDefinition --output json > "$D/td-actual.json"

# OPCION=A  → solo health check
# OPCION=B  → health check + cpu/memoria
OPCION=A node -e '
const fs = require("fs");
const t  = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));

// Campos que devuelve describe pero register no acepta
for (const k of ["taskDefinitionArn","revision","status","requiresAttributes",
                 "compatibilities","registeredAt","registeredBy"]) delete t[k];

const c = t.containerDefinitions.find(x => x.name === "bible-references");
if (!c) throw new Error("no se encontró el contenedor bible-references");

c.healthCheck.startPeriod = 180;
c.healthCheck.timeout     = 15;

if (process.env.OPCION === "B") { t.cpu = "1024"; t.memory = "2048"; }

fs.writeFileSync(process.argv[2], JSON.stringify(t, null, 2));
console.log("cpu:", t.cpu, "| memory:", t.memory);
console.log("healthCheck:", JSON.stringify(c.healthCheck));
' "$(cygpath -w "$D/td-actual.json")" "$(cygpath -w "$D/td-nueva.json")"
```

Revisar el diff antes de registrar:

```bash
diff <(node -e 'console.log(JSON.stringify(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")),null,2))' "$(cygpath -w "$D/td-actual.json")") \
     "$D/td-nueva.json"
```

### 2 · Registrar

```bash
aws ecs register-task-definition \
  --cli-input-json "file://$(cygpath -w "$D/td-nueva.json")" \
  --query 'taskDefinition.{Family:family,Revision:revision,Cpu:cpu,Memory:memory}' \
  --output table
```

Anotar el número de revisión que devuelve (será la 18 si nadie registró otra entretanto).

### 3 · Desplegarla

```bash
aws ecs update-service \
  --cluster biblia-cluster \
  --service bible-references-service \
  --task-definition bible-references-task:18 \
  --query 'service.{TaskDef:taskDefinition,Desired:desiredCount}' \
  --output table

aws ecs wait services-stable --cluster biblia-cluster --services bible-references-service
```

### 4 · Verificar

```bash
# No deben aparecer eventos nuevos de "failed container health checks"
aws ecs describe-services --cluster biblia-cluster --services bible-references-service \
  --query 'services[].events[:10].[createdAt,message]' --output text

# El target debe quedar healthy y mantenerse
aws elbv2 describe-target-health \
  --target-group-arn 'arn:aws:elasticloadbalancing:mx-central-1:274869222183:targetgroup/bible-tg/29e3a2cf8e88e754' \
  --query 'TargetHealthDescriptions[].{Target:Target.Id,State:TargetHealth.State}' --output table
```

El criterio de éxito es **ningún reemplazo durante 30 minutos**. El bucle actual produce
uno cada 5–10 minutos, así que media hora limpia es señal suficiente.

Conviene confirmar también el tiempo de arranque del task nuevo, para saber si los 180 s
de gracia dan margen holgado o quedan justos:

```bash
S=$(aws logs describe-log-streams --log-group-name /ecs/bible-references \
      --order-by LastEventTime --descending --max-items 1 \
      --query 'logStreams[0].logStreamName' --output text | head -1)
aws logs get-log-events --log-group-name /ecs/bible-references --log-stream-name "$S" \
  --limit 500 --output json > "$D/nuevo.json"
grep -o 'Started BibleReferencesApplication in [0-9.]* seconds' "$D/nuevo.json"
```

### 5 · Reversión

```bash
aws ecs update-service --cluster biblia-cluster --service bible-references-service \
  --task-definition bible-references-task:17
aws ecs wait services-stable --cluster biblia-cluster --services bible-references-service
```

La revisión 17 sigue registrada y disponible; volver a ella restaura el estado previo,
bucle de reinicio incluido.

---

## Sincronizar el repositorio

Independiente del despliegue, pero conviene: dejar `task-definition.json` reflejando lo
que realmente corre, para que el archivo no siga mintiendo.

```bash
cp "$D/td-nueva.json" task-definition.json
```

Mejor aún sería invertir la relación y que el repo fuera la fuente de verdad: eliminar el
paso `Download current task definition` del workflow y dejar que
`amazon-ecs-render-task-definition` parta del archivo versionado. Así los cambios de CPU,
memoria o health check pasarían por revisión de código en vez de aplicarse a mano. Es un
cambio de pipeline aparte de este runbook.

---

## Notas

**El health check del ALB y el del contenedor son independientes.** El del ALB
(`/actuator/health` vía target group) ya reporta `healthy`; el que falla es el del
contenedor, definido en la task definition. Este runbook solo toca el segundo.

**El arranque de 64 s no es anormal** para Spring Boot con Hibernate, 6 repositorios JPA y
Hazelcast embebido en 0,5 vCPU. No hay nada que "arreglar" en la aplicación; el problema
es que la configuración del health check no contemplaba ese tiempo.

**Este runbook no toca los hallazgos 1 ni 2.** La base de datos sigue expuesta en 5432
(hallazgo 1, sin aplicar). La fase 1 del hallazgo 2 ya está aplicada; la fase 2 no.

---

## PENDIENTE — Restaurar el health check tras desplegar la imagen con `curl`

El `Dockerfile` ya instala `curl`, pero esa imagen **solo llega a producción con el
próximo push a `main`**. Hasta entonces, la revisión 19 corre sin health check de
contenedor, cubierta por el del ALB.

Una vez que el despliegue haya publicado la imagen nueva, conviene confirmar que `curl`
está realmente en la imagen que corre:

```bash
aws ecr get-login-password --region mx-central-1 \
  | docker login --username AWS --password-stdin 274869222183.dkr.ecr.mx-central-1.amazonaws.com
docker pull 274869222183.dkr.ecr.mx-central-1.amazonaws.com/bible-references:latest
docker run --rm --entrypoint sh \
  274869222183.dkr.ecr.mx-central-1.amazonaws.com/bible-references:latest -c "command -v curl"
```

Si devuelve `/usr/bin/curl`, restaurar el chequeo como revisión nueva:

```bash
export AWS_PAGER=""
export PYTHONUTF8=1
D="$USERPROFILE/AppData/Local/Temp"

aws ecs describe-task-definition --task-definition bible-references-task \
  --query taskDefinition --output json > "$D/td-actual.json"

node -e '
const fs = require("fs");
const t  = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
for (const k of ["taskDefinitionArn","revision","status","requiresAttributes",
                 "compatibilities","registeredAt","registeredBy"]) delete t[k];
const c = t.containerDefinitions.find(x => x.name === "bible-references");
c.healthCheck = {
  command: ["CMD-SHELL","curl -f http://localhost:8080/actuator/health || exit 1"],
  interval: 30, timeout: 15, retries: 3, startPeriod: 180
};
fs.writeFileSync(process.argv[2], JSON.stringify(t, null, 2));
console.log("healthCheck restaurado");
' "$(cygpath -w "$D/td-actual.json")" "$(cygpath -w "$D/td-nueva.json")"

aws ecs register-task-definition --cli-input-json "file://$(cygpath -w "$D/td-nueva.json")" \
  --query 'taskDefinition.revision' --output text
```

Desplegar con `update-service` y **verificar que `healthStatus` llegue a `HEALTHY`**, no a
`UNHEALTHY`:

```bash
T=$(aws ecs list-tasks --cluster biblia-cluster --service-name bible-references-service \
      --query 'taskArns[0]' --output text | head -1)
aws ecs describe-tasks --cluster biblia-cluster --tasks "$T" \
  --query 'tasks[].healthStatus' --output text
```

Se conservan `startPeriod: 180` y `timeout: 15` de la revisión 18: aunque no eran la causa
del bucle, los 64 s de arranque medidos dejaban muy poco margen frente a los 60 s
originales, y ampliarlos no cuesta nada.

Si el chequeo vuelve a fallar, la salida rápida es volver a la revisión 19:

```bash
aws ecs update-service --cluster biblia-cluster --service bible-references-service \
  --task-definition bible-references-task:19
```
