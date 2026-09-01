# Runbook — volver de Lambda a ECS

> 🛑 **ESTE RUNBOOK YA NO ES APLICABLE. `bible-alb` se borró el 2026-08-15.**
>
> El procedimiento de abajo consiste en devolver el `TargetOriginId` de `/api/*` al origen
> `Backend-API`, que apunta a un balanceador **que ya no existe**. Ejecutarlo tal cual dejaría
> `/api/*` apuntando a un DNS muerto. Se conserva porque describe correctamente el resto del
> camino —el origen, la distribución, las comprobaciones— y porque esos pasos siguen haciendo
> falta *después* de reconstruir el balanceador.
>
> **Volver hoy exige, por este orden:** recrear el ALB, el target group y el listener con su
> regla de `X-Origin-Verify` (configuración en `alb-borrado-configuracion.md`, o `terraform/`
> si se adopta el state); subir el servicio ECS a `desiredCount 1` y esperar a que la task
> pase a `healthy`; **crear un origen nuevo en CloudFront con el DNS nuevo** —el del ALB
> recreado no será el de antes— y solo entonces seguir desde el paso 1 de aquí.
>
> Se pasó de ~10 minutos a horas. Era el coste conocido de cruzar a la fase C y se aceptó a
> sabiendas; ver `migracion-lambda.md`.

Procedimiento para devolver el tráfico de `/api/*` al ALB si la arquitectura de Lambda no
convence. Escrito **antes** de cortar, que es cuando se piensa con claridad.

Aplicado el cambio de origen el **2026-08-14**.

> Este runbook cubría la **fase B** de la migración: CloudFront apunta a API Gateway y la
> arquitectura anterior sigue en pie. Dejó de servir al darse de baja el ALB el 2026-08-15;
> ver "Punto de no retorno" al final.

## Resumen

| | |
|---|---|
| Qué se cambia | Un campo: `TargetOriginId` del comportamiento `/api/*` |
| De | `Backend-Lambda` — API Gateway `m9zcdngc1m`, stage `prod` |
| A | `Backend-API` — ALB `bible-alb-1227546912.mx-central-1.elb.amazonaws.com` |
| Distribución | `E1MTT1XP2UUMYU` |
| Tiempo hasta que surte efecto | ~10 min de propagación |
| Qué NO hay que tocar | El ALB, su listener, la regla del header, el servicio ECS |
| Riesgo | Ninguno mientras el origen `Backend-API` siga declarado en la distribución |

El origen del ALB **no se borra** al migrar. Sigue declarado en la distribución sin recibir
tráfico, y eso es justamente lo que hace barata la vuelta atrás.

> **Este equipo no tiene `jq`.** Los ejemplos van en PowerShell, que es lo que hay. Un
> runbook con comandos que no se pueden ejecutar es peor que no tenerlo, porque el fallo
> solo se descubre en el momento de usarlo.

## Paso 0 — comprobar que ECS puede recibir el tráfico

> **⚠️ Corrección del 2026-08-14: el arranque automático está DESACTIVADO.**
> `bible-ecs-stop` (23:00 CDMX) está **ENABLED** pero `bible-ecs-start` (07:55) está
> **DISABLED**. El servicio se para cada noche y **no vuelve solo a ninguna hora**.
>
> Lo que sigue decía que la franja crítica era de 23:00 a 07:55. **Ya no es así: a cualquier
> hora del día puede haber 0 tasks.** Dar por hecho que a media mañana el servicio está arriba
> es el error que este runbook debe evitar. Comprobarlo siempre, y contar con el minuto extra
> de arranque como parte del procedimiento, no como excepción nocturna.
>
> Mientras `bible-ecs-start` siga desactivado, este paso **nunca** es opcional.

Volver atrás con el servicio a 0 exige subirlo primero y esperar el arranque (~1 min más).

```powershell
$CL = 'biblia-cluster'; $SV = 'bible-references-service'
aws ecs describe-services --cluster $CL --services $SV `
  --query 'services[0].{Deseadas:desiredCount,Corriendo:runningCount}' --output json

# Si Deseadas es 0:
aws ecs update-service --cluster $CL --service $SV --desired-count 1
aws ecs wait services-stable --cluster $CL --services $SV
```

**La comprobación buena es la salud del target group, no un `curl`.** El ALB solo admite
tráfico de la prefix list de CloudFront, así que desde un portátil **no se puede alcanzar**:
un `curl` directo expira siempre, tanto si la aplicación está sana como si no.

```powershell
$TG = aws elbv2 describe-target-groups --query 'TargetGroups[0].TargetGroupArn' --output text
aws elbv2 describe-target-health --target-group-arn $TG `
  --query 'TargetHealthDescriptions[].TargetHealth.State' --output text
# esperado: healthy
```

## Paso 1 — devolver el origen

```powershell
$DIST = 'E1MTT1XP2UUMYU'
$resp = aws cloudfront get-distribution-config --id $DIST --output json | ConvertFrom-Json
$etag = $resp.ETag
$dc   = $resp.DistributionConfig

($dc.CacheBehaviors.Items | Where-Object { $_.PathPattern -eq '/api/*' }).TargetOriginId = 'Backend-API'

$json = $dc | ConvertTo-Json -Depth 50
[System.IO.File]::WriteAllText("$env:TEMP\cf-revertida.json", $json, (New-Object System.Text.UTF8Encoding($false)))

aws cloudfront update-distribution --id $DIST `
  --distribution-config ("file://" + "$env:TEMP\cf-revertida.json") --if-match $etag `
  --query 'Distribution.DistributionConfig.CacheBehaviors.Items[0].TargetOriginId' --output text
```

Dos detalles que hacen fallar esto si se cambian:

- **`-Depth 50`**: por defecto `ConvertTo-Json` corta a 2 niveles y enviaría una config
  mutilada que CloudFront acepta parcialmente. No bajarlo.
- **UTF-8 sin BOM**: `Out-File -Encoding utf8` en PowerShell 5.1 escribe BOM y la CLI falla
  al parsear el JSON. De ahí el `WriteAllText` con `UTF8Encoding($false)`.

Este es el mismo procedimiento con el que se aplicó el cambio de ida, así que está probado
en esta distribución concreta, no solo en teoría.

## Paso 2 — verificar

```powershell
aws cloudfront wait distribution-deployed --id E1MTT1XP2UUMYU

curl.exe -s -o NUL -w "%{http_code}\n" `
  -H "X-Client-Origin: https://escrituraclave.com" `
  https://escrituraclave.com/api/bible/chapter/1/1
```

**La cabecera `X-Client-Origin` es obligatoria en toda comprobación manual.** Sin ella la
respuesta es 403 y podría interpretarse como que la vuelta atrás ha fallado.

Confirmar que el tráfico ya no llega a Lambda: las invocaciones deben cesar.

```powershell
aws logs tail /aws/lambda/bible-references --since 5m
```

Si se había asociado la política de caché del borde, purgar lo que quedara guardado:

```powershell
aws cloudfront create-invalidation --id E1MTT1XP2UUMYU --paths '/api/*'
```

## Después de volver

Nada más es obligatorio: la arquitectura queda exactamente como antes de la migración. Lo
que conviene decidir es qué hacer con lo que queda desconectado.

| Pieza | Recomendación |
|---|---|
| API Gateway `m9zcdngc1m` | Dejarla. No cuesta nada sin tráfico y ahorra rehacerla en un segundo intento |
| Función Lambda | Dejarla. Sin invocaciones no cuesta |
| `ORIGIN_VERIFY_SECRET` en la función | Dejarlo. Con la función sin tráfico no expone nada nuevo |
| `OriginVerifyFilter` en la aplicación | Dejarlo. Sin la variable de entorno está inactivo y ECS no lo nota |
| Origen `Backend-Lambda` en CloudFront | Dejarlo. Un origen sin comportamiento asociado no recibe tráfico |

Borrar la API y la función solo si se abandona la migración del todo.

## Punto de no retorno

Todo lo anterior vale **mientras el ALB siga existiendo**. Darlo de baja convierte esto en
horas de reconstrucción manual: un ALB nuevo tiene otro nombre DNS, así que además hay que
rehacer la regla del listener con el header secreto —que solo vive en la configuración de
CloudFront—, el target group con su health check, las reglas de los grupos de seguridad, el
servicio y la task definition.

**Antes de dar de baja el ALB hay que adoptar el state de Terraform** (hallazgo 8). El
módulo de `terraform/` ya declara todo el camino actual, pero sin state adoptado no puede
reconstruir nada; es documentación ejecutable, no una vía de recuperación. Con el state
adoptado, la reconstrucción pasa a ser un `terraform apply`.

## Criterios para cerrar el periodo de prueba

Mantener ambas arquitecturas cuesta ~31 USD/mes, que es exactamente lo que la migración
pretende ahorrar: **el ahorro y la red de seguridad son mutuamente excluyentes**. Conviene
que el periodo tenga fecha y condiciones fijadas de antemano, no que se quede abierto.

Propuesta: **dos semanas desde el 2026-08-14**, es decir hasta el **2026-08-28**, cerrando
en verde si se cumple todo:

- Ninguna respuesta 5xx atribuible a la función.
- Latencia dentro de lo medido: ~200 ms en caliente, ~5 s la primera petición de un entorno
  nuevo. Si aparecen tiempos muy por encima de esos 5 s de forma repetida, investigar antes
  de cerrar.
- Ningún error de conexión a la base de datos tras una restauración de SnapStart —el
  síntoma sería una excepción de Hikari en la primera invocación de un entorno nuevo—.
- El frontend funcionando en navegación normal, **incluida la búsqueda**, que es lo único
  que no cubren las pruebas locales por depender de `unaccent` de PostgreSQL.