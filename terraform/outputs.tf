output "alb_dns_name" {
  description = "DNS del balanceador. Solo alcanzable desde CloudFront y con el header secreto."
  value       = aws_lb.main.dns_name
}

output "cloudfront_domain" {
  description = "Dominio de la distribución."
  value       = aws_cloudfront_distribution.main.domain_name
}

output "ecr_repository_url" {
  description = "URL del repositorio de imágenes, usada por el pipeline."
  value       = aws_ecr_repository.app.repository_url
}

output "rds_endpoint" {
  description = "Endpoint de la base de datos. HALLAZGO 1: hoy resuelve a una IP pública."
  value       = aws_db_instance.main.endpoint
}

output "hallazgos_abiertos" {
  description = "Recordatorio de lo que este inventario documenta pero no corrige."
  value = {
    "1_critico" = "RDS acepta 5432 desde 0.0.0.0/0 (security_groups.tf, rds.tf)"
    "3_medio"   = "RDS single-AZ, backup 1 dia, sin deletion protection (rds.tf)"
    "4_medio"   = "Hazelcast con multicast, no funciona en VPC (hazelcast.yaml)"
    "5_medio"   = "task role = execution role, con SecretsManagerReadWrite (iam.tf)"
    "6_medio"   = "GitHub Actions usa llaves estaticas en vez de OIDC"
    "7_bajo"    = "biblia-sg y biblia-alb-sg huerfanos, sin importar a proposito"
  }
}
