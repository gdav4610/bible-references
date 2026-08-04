terraform {
  # Los bloques `import` requieren Terraform >= 1.5
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # >= 6.0 es necesario: la distribución usa la política TLSv1.3_2025, que
      # los proveedores 5.x no reconocen como valor válido.
      version = "~> 6.0"
    }
  }

  # IMPORTANTE: el state contiene valores sensibles (el header secreto de CloudFront,
  # atributos de RDS). Antes de cualquier `apply` real, mover el state a un backend
  # remoto cifrado. Ejemplo:
  #
  # backend "s3" {
  #   bucket       = "<bucket-de-state>"
  #   key          = "bible-references/terraform.tfstate"
  #   region       = "mx-central-1"
  #   encrypt      = true
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.region

  # Sin `default_tags` a propósito. Hoy NINGÚN recurso de esta cuenta tiene tags,
  # así que añadirlas sería un cambio y este módulo dejaría de reflejar el estado
  # real. Etiquetar todo es una buena primera mejora, pero como cambio explícito
  # después de que el plan salga limpio, no mezclado con la adopción inicial.
}
