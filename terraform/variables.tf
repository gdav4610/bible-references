variable "region" {
  description = "Región de AWS donde vive todo salvo ACM y WAF, que son globales."
  type        = string
  default     = "mx-central-1"
}

variable "account_id" {
  description = "ID de la cuenta de AWS."
  type        = string
  default     = "274869222183"
}

variable "project" {
  description = "Nombre base del proyecto."
  type        = string
  default     = "bible-references"
}

# ---------------------------------------------------------------------------
# Recursos existentes que este módulo NO gestiona, solo referencia
# ---------------------------------------------------------------------------

variable "vpc_id" {
  description = "VPC por defecto de la cuenta. No la gestiona Terraform."
  type        = string
  default     = "vpc-0a224eceeb1c0720a"
}

variable "acm_certificate_arn" {
  description = <<-EOT
    Certificado de escrituraclave.com en us-east-1, validado por DNS.
    No lo gestiona Terraform: no hay hosted zone en Route53 (el DNS es externo),
    así que no se pueden crear los registros de validación desde aquí.
  EOT
  type        = string
  default     = "arn:aws:acm:us-east-1:274869222183:certificate/42b86bf4-1c80-4312-b162-acb98e024bca"
}

variable "waf_web_acl_arn" {
  description = <<-EOT
    WebACL creada desde la consola de CloudFront. No la gestiona Terraform;
    se referencia por ARN para no perder la asociación con la distribución.
  EOT
  type        = string
  default     = "arn:aws:wafv2:us-east-1:274869222183:global/webacl/CreatedByCloudFront-228216dd/49dcae95-4901-4004-92b8-640178961cef"
}

# ---------------------------------------------------------------------------
# Valores sensibles
# ---------------------------------------------------------------------------

variable "origin_verify_secret" {
  description = <<-EOT
    Header secreto que CloudFront inyecta y el listener del ALB exige (hallazgo 2).

    NUNCA escribir este valor en un archivo versionado. Pasarlo por entorno:

      export TF_VAR_origin_verify_secret=$(aws cloudfront get-distribution-config \
        --id E1MTT1XP2UUMYU \
        --query 'DistributionConfig.Origins.Items[?Id==`Backend-API`].CustomHeaders.Items[0].HeaderValue' \
        --output text)

    Aun así queda en el state en claro: por eso el backend debe estar cifrado.
  EOT
  type        = string
  sensitive   = true
}

variable "container_image" {
  description = "Imagen desplegada actualmente. La actualiza el pipeline, no Terraform."
  type        = string
  default     = "274869222183.dkr.ecr.mx-central-1.amazonaws.com/bible-references:0c566343f01cd6bf4d9fb81f3d18970de3681568"
}
