resource "aws_ecr_repository" "app" {
  name                 = "bible-references"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = false
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# ---------------------------------------------------------------------------
# Política de ciclo de vida — aplicada el 2026-08-04
#
# Antes no existía ninguna: el pipeline etiqueta cada build con el SHA del commit
# y además mueve `latest`, así que el repositorio acumulaba una imagen por push
# sin que nada las expirara. Había llegado a 18 imágenes y 2,87 GB.
#
# `tagStatus: any` es deliberado y basta con una sola regla. Aquí NINGUNA imagen
# llega a quedar `untagged`: cada build recibe su SHA propio, que no se mueve, así
# que una regla separada para untagged no tendría nada que seleccionar. Con `any`
# quedan cubiertas de todos modos si algún día cambia el etiquetado.
#
# CUIDADO al bajar `countNumber`: conservar 2 significa la imagen desplegada más
# UNA anterior. Es un solo paso de rollback; dos despliegues seguidos y la versión
# de hace dos ya no existe. Es la política que se pidió explícitamente.
#
# Verificado antes de aplicar con `start-lifecycle-policy-preview`: la imagen que
# corre en producción (`0c566343…`, revisión :21) queda entre las conservadas.
# ---------------------------------------------------------------------------

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Conservar solo las 2 imagenes mas recientes; expirar el resto"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 2
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}
