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
# No hay política de ciclo de vida.
#
# El pipeline etiqueta cada build con el SHA del commit y además mueve `latest`,
# así que el repositorio acumula una imagen por push sin que nada las expire. Con
# imágenes de JRE + aplicación esto crece rápido.
#
# Una política razonable sería conservar las últimas 10 etiquetadas con SHA y
# expirar las `untagged` a los pocos días. No se añade aquí porque cambiaría el
# estado actual: este módulo es un inventario, no una mejora.
# ---------------------------------------------------------------------------
