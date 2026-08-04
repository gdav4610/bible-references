# ---------------------------------------------------------------------------
# Secreto de la base de datos
#
# Terraform gestiona el CONTENEDOR del secreto, no su VALOR. La versión con las
# credenciales (`url`, `username`, `password`) queda deliberadamente fuera: si se
# gestionara con aws_secretsmanager_secret_version, la contraseña acabaría en
# claro dentro del state.
#
# Para rotarla, usar la consola o la CLI, no Terraform.
# ---------------------------------------------------------------------------

resource "aws_secretsmanager_secret" "db" {
  name = "bible-references/db"
  # Valores por defecto del proveedor. Se declaran explícitamente porque la API
  # no los devuelve al importar y, omitidos, aparecen como cambio en cada plan.
  recovery_window_in_days        = 30
  force_overwrite_replica_secret = false
}

# ---------------------------------------------------------------------------
# Logs
#
# Retención de 14 días, aplicada a mano desde la consola el 2026-08-04 (usuario
# `gdgr`, confirmado en CloudTrail). Antes no expiraban nunca y se facturaban
# para siempre; con el bucle de reinicio del hallazgo 0 este grupo llegó a
# acumular ~58 flujos en unas horas.
#
# `/ecs/biblia-frontend-task` se eliminó el 2026-08-04: era un grupo huérfano de
# una familia de tasks sin revisiones activas, con su último evento en abril.
# `/ecs/bible-references` es hoy el único log group de la cuenta.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "ecs" {
  name              = "/ecs/bible-references"
  log_group_class   = "STANDARD"
  retention_in_days = 14
}

# ---------------------------------------------------------------------------
# Bucket del frontend
#
# Bien configurado: los cuatro flags de acceso público bloqueados y una política
# que solo permite a esta distribución de CloudFront leer objetos, vía Origin
# Access Control. El bundle no es accesible directamente.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "frontend" {
  bucket = "biblia-frontend-prod"
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket                  = aws_s3_bucket.frontend.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  policy = jsonencode({
    Version = "2008-10-17"
    Id      = "PolicyForCloudFrontPrivateContent"
    Statement = [
      {
        Sid       = "AllowCloudFrontServicePrincipal"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.frontend.arn}/*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = aws_cloudfront_distribution.main.arn
          }
        }
      }
    ]
  })
}
