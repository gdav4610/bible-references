# `bible-alb` — configuración al borrarlo (2026-08-15)

El balanceador se borró el **2026-08-15**, junto con sus dos Elastic IP. Este documento existe
para que la configuración no desaparezca con él: es lo que habría que recrear si algún día se
vuelve a la arquitectura de ECS. **No sustituye al módulo de `terraform/`**, que declara lo
mismo y además el servicio y la task definition; sirve para verificar que aquél coincide con lo
que había de verdad.

> Ver `docs/migracion-lambda.md` para el porqué del borrado y `docs/runbook-vuelta-atras-lambda.md`
> para el procedimiento de vuelta atrás, que **a partir de esta fecha ya no es de diez minutos**.

## Lo que había

| Recurso | Valor |
|---|---|
| Nombre | `bible-alb` (application, `internet-facing`, `ipv4`) |
| ARN | `arn:aws:elasticloadbalancing:mx-central-1:274869222183:loadbalancer/app/bible-alb/6372b1995d9da0cf` |
| Creado | 2026-04-07 |
| VPC | `vpc-0a224eceeb1c0720a` |
| Subredes | `subnet-05d58e02a68c1526d`, `subnet-0d422b51e4fa87fcd` |
| Security group | `sg-0f48e25801a170bef` |
| Elastic IP | `78.14.156.106`, `78.14.161.153` — **liberadas**, no se recuperan |

### Listener y reglas

Un único listener **HTTP :80**. El orden importa y es el que documenta
`approach-spec-driven-development.md`: la acción por defecto rechaza, y una regla explícita
deja pasar solo lo que trae la cabecera del CDN.

| Prioridad | Condición | Acción |
|---|---|---|
| 1 | cabecera **`X-Origin-Verify`** con el valor compartido con CloudFront | `forward` a `bible-tg` |
| default | — | `fixed-response` **403** `text/plain` con cuerpo `Forbidden` |

> **El valor del secreto no está aquí a propósito.** Vivía en la configuración de CloudFront y
> en la variable `ORIGIN_VERIFY_SECRET` de la función Lambda. **Se rotó el 2026-08-23** (tarea 15):
> el valor que hubo hasta esa fecha ya no sirve para nada, y si alguna vez se recrea esta regla hay
> que tomar el vigente del origen `Backend-Lambda` de CloudFront.
>
> En esa misma rotación se **borró el origen `Backend-API`** de la distribución. Apuntaba al DNS del
> balanceador ya inexistente y conservaba una tercera copia del secreto viejo. Recrear el ALB exige
> ahora crear también ese origen desde cero, que es lo que ya advertía el runbook de vuelta atrás.

### Target group `bible-tg`

Sigue existiendo tras borrar el balanceador —un target group huérfano no cuesta nada— pero sin
tráfico.

| Propiedad | Valor |
|---|---|
| Protocolo / puerto | HTTP 8080 |
| Tipo de destino | `ip` (obligado por `awsvpc` de Fargate) |
| VPC | `vpc-0a224eceeb1c0720a` |
| Health check | `/actuator/health`, intervalo 30 s, timeout 5 s |
| Umbrales | 2 sano / 3 insano, matcher **200** |

> El health check del balanceador y el del contenedor eran independientes y podían discrepar
> sin que nada lo advirtiera; por eso ambos apuntaban al mismo endpoint. Ese detalle costó un
> incidente entero y está en `docs/runbook-hallazgo-0-bucle-reinicio.md`.

## Qué cuesta ahora recrearlo

Recrear el ALB da un **nombre DNS nuevo**, así que no basta con levantarlo: hay que reapuntar
el origen de `/api/*` en CloudFront y rehacer la regla del listener con el secreto. Súmense el
target group, las reglas de los grupos de seguridad y el servicio de ECS con su task
definition. **El módulo de `terraform/` declara todo eso**, pero su state sigue sin adoptarse,
de modo que hoy es documentación ejecutable y no una vía de recuperación.
