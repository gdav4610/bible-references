# Terraform — estado actual de AWS

Este módulo **describe la infraestructura tal como existe hoy**, no una versión
mejorada de ella. Su propósito es doble:

1. Convertir en código auditable algo que hasta ahora solo existía en la consola
   (hallazgo 8 de `docs/aws-architecture.md`).
2. Servir de inventario completo si se evalúa migrar a otra nube: no se puede
   estimar el costo de replicar lo que no está escrito.

Los hallazgos abiertos —RDS expuesto a internet, roles IAM sin separar, Hazelcast
con multicast— quedan **declarados tal cual están**, con comentarios que explican
qué está mal y cómo se corrige. Arreglarlos aquí haría que `plan` propusiera
cambios y este módulo dejaría de ser un espejo del estado real.

## Criterio de aceptación

El objetivo era `No changes`. El plan real, verificado el 2026-07-31 con
Terraform 1.9 y el proveedor AWS 6.57.1, es:

```
Plan: 27 to import, 0 to add, 3 to change, 0 to destroy.
```

**Nada se crea y nada se destruye.** Los 27 imports son la adopción esperada. Los
3 cambios restantes no corresponden a diferencias reales con AWS y aplicarlos es
un no-op:

| Recurso | Por qué aparece |
|---|---|
| `aws_secretsmanager_secret.db` | `recovery_window_in_days` y `force_overwrite_replica_secret` son atributos de solo escritura: la API nunca los devuelve, así que Terraform no puede compararlos con nada |
| `aws_lb_listener_rule` | La condición contiene el header secreto. Al estar marcado `sensitive`, Terraform se niega a renderizar el bloque y lo trata como cambiado por precaución |
| `aws_cloudfront_distribution` | Mismo motivo: el `custom_header` sensible está dentro de un bloque `origin`, y eso suprime el diff de todo el conjunto |

Los dos últimos desaparecerían quitando `sensitive = true` de
`var.origin_verify_secret`, pero entonces el secreto se imprimiría en cada plan.
Se prefirió el diff cosmético.

Antes de llegar aquí el plan proponía 16 cambios; todos eran discrepancias reales
del código y se corrigieron. Vale la pena repetir el ejercicio si algo cambia:
**si aparece un recurso `to add` o `to destroy`, hay un error de verdad.**

## Puesta en marcha

```bash
cd terraform

# El header secreto no vive en ningún archivo versionado.
export TF_VAR_origin_verify_secret=$(aws cloudfront get-distribution-config \
  --id E1MTT1XP2UUMYU \
  --query 'DistributionConfig.Origins.Items[?Id==`Backend-API`].CustomHeaders.Items[0].HeaderValue' \
  --output text)

terraform init
terraform plan
```

En Windows con Git Bash, exportar antes `PYTHONUTF8=1` y `MSYS_NO_PATHCONV=1`;
si no, la AWS CLI falla al leer valores con caracteres no ASCII y Git Bash
convierte las rutas que empiezan por `/`.

**No ejecutar `terraform apply` hasta que el plan salga sin cambios.** Cuando lo
haga, el `apply` solo escribirá el state: no tocará nada en AWS.

## Interpretar el plan

| El plan propone | Significa | Qué hacer |
|---|---|---|
| `will be created` | Falta un bloque `import` | Añadirlo en `imports.tf` |
| `will be updated` | La declaración no coincide con la realidad | Corregir el `.tf` con el valor real, no aplicar el cambio |
| `will be destroyed` | Hay un recurso en el state que ya no está declarado | Revisar antes de nada |

Discrepancias reales encontradas y corregidas durante la adopción, por si sirven
de referencia al importar recursos parecidos:

| Síntoma | Causa |
|---|---|
| 16 recursos con cambio de `tags_all` | El bloque `default_tags` del proveedor añadía etiquetas; **ningún recurso de esta cuenta tiene tags hoy** |
| `unhealthy_threshold 3 -> 2` en el target group | Valor declarado a ojo en vez de leído de la API |
| `max_allocated_storage` en RDS | El autoescalado de almacenamiento estaba activo y no se había declarado |
| Origen `Backend-API` de CloudFront a `null` | Faltaba `ip_address_type` en `custom_origin_config`, lo que cambiaba el hash del elemento del conjunto |
| Reglas de SG "to be created" | Existían en AWS pero faltaban sus bloques `import` |
| Descripciones en reglas de SG | Se habían inventado al escribir el código; las reglas reales no tienen |
| Error de `TLSv1.3_2025` | El proveedor 5.x no conoce esa política; hizo falta subir a 6.x |
| ARN duplicado en el cluster de ECS | El `import` del cluster va por nombre, no por ARN |

Una vez que el plan esté en el estado descrito arriba y se haya hecho `apply`,
`imports.tf` puede borrarse.

## Conflicto conocido con el pipeline

`.github/workflows/aws-dep.yml` ejecuta:

```bash
aws ecs describe-task-definition --task-definition bible-references-task \
  --query taskDefinition > task-definition.json
```

Es decir, **descarga la task definition viva y despliega eso**, registrando una
revisión nueva en cada push. Terraform y el pipeline compiten por el mismo
recurso.

Mientras siga así, este módulo lo evita con `ignore_changes` sobre
`container_definitions` y `task_definition`. Funciona, pero es un parche: la
imagen desplegada y la declarada aquí divergen entre despliegues.

La solución real es invertir la relación —eliminar el paso de descarga y dejar
que `amazon-ecs-render-task-definition` parta de un archivo versionado— para que
los cambios de CPU, memoria o health check pasen por revisión de código. Es un
cambio de pipeline, no de este módulo.

## Seguridad del state

El state contiene en claro:

- el header secreto de CloudFront (`X-Origin-Verify`)
- atributos de RDS

Antes de cualquier uso compartido o automatizado, mover el state a un backend
remoto cifrado; hay un ejemplo comentado en `versions.tf`. El `.gitignore` de
esta carpeta ya excluye `*.tfstate`, `*.tfvars` y `*.tfplan`, pero conviene
verificar que nunca se haya versionado ninguno.

Lo que **no** entra al state a propósito: la versión del secreto de Secrets
Manager, que contiene la contraseña de la base de datos. Terraform gestiona el
contenedor del secreto, no su contenido.

## Fuera de alcance

| Recurso | Por qué |
|---|---|
| Certificado ACM (us-east-1) | No hay hosted zone en Route53; el DNS es externo y Terraform no puede crear los registros de validación |
| WAF WebACL | Creada desde la consola de CloudFront; se referencia por ARN para no perder la asociación |
| VPC, subredes, tabla de rutas | Son recursos de la cuenta, no del proyecto |
| `biblia-sg`, `biblia-alb-sg` | Huérfanos (0 interfaces). El objetivo es borrarlos, no adoptarlos |
| Versión del secreto de BD | Contiene la contraseña |

## Estructura

```
versions.tf              proveedor, versión, backend (comentado)
variables.tf             región, cuenta, ARNs externos, el secreto sensible
data.tf                  VPC y subredes existentes, prefix list de CloudFront
security_groups.tf       SG del ALB, de las tasks, y el `default` (hallazgo 1)
alb.tf                   balanceador, target group, listener y regla del header
ecs.tf                   cluster, task definition y servicio
ecr.tf                   repositorio de imágenes
iam.tf                   rol de ejecución (hallazgo 5)
rds.tf                   base de datos (hallazgos 1 y 3)
storage_and_secrets.tf   secreto, log group y bucket del frontend
cloudfront.tf            distribución, OAC y comportamientos
imports.tf               adopción de lo existente; borrable tras el primer apply
outputs.tf               endpoints y recordatorio de hallazgos abiertos
```
