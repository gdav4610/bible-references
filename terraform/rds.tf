# ---------------------------------------------------------------------------
# Base de datos — HALLAZGO 1, CRÍTICO Y SIN RESOLVER
#
# Tal como está hoy, esta instancia acepta conexiones desde cualquier host de
# internet. Tres condiciones se combinan:
#
#   1. publicly_accessible = true
#   2. vive en el subnet group por defecto, cuyas subredes son públicas
#      (tabla de rutas principal con 0.0.0.0/0 -> internet gateway)
#   3. su grupo de seguridad es el `default` de la VPC, que permite
#      5432 desde 0.0.0.0/0 (ver security_groups.tf)
#
# Lo único que separa los datos de un atacante es la contraseña.
#
# La corrección, en orden: cerrar primero la regla del grupo de seguridad
# —es el paso que realmente elimina la exposición—, después poner
# publicly_accessible = false, y por último mover la instancia a subredes
# privadas con un subnet group propio. Ver docs/aws-architecture.md.
#
# También aplica el hallazgo 3: single-AZ, 1 día de retención de backups y sin
# protección contra borrado.
# ---------------------------------------------------------------------------

resource "aws_db_instance" "main" {
  identifier     = "database-2"
  engine         = "postgres"
  engine_version = "17.9"
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  # Autoescalado de almacenamiento activo: crece solo hasta 1 TB.
  max_allocated_storage = 1000
  storage_type          = "gp2"
  storage_encrypted     = true

  username = "postgres"

  db_subnet_group_name   = "default-vpc-0a224eceeb1c0720a"
  vpc_security_group_ids = [aws_default_security_group.default.id]
  publicly_accessible    = true # HALLAZGO 1
  network_type           = "IPV4"
  availability_zone      = "mx-central-1a"
  multi_az               = false # HALLAZGO 3

  parameter_group_name         = "default.postgres17"
  ca_cert_identifier           = "rds-ca-rsa2048-g1"
  auto_minor_version_upgrade   = true
  maintenance_window           = "thu:20:47-thu:21:17"
  backup_window                = "22:32-23:02"
  backup_retention_period      = 1     # HALLAZGO 3
  deletion_protection          = false # HALLAZGO 3
  performance_insights_enabled = true
  copy_tags_to_snapshot        = true
  apply_immediately            = false

  skip_final_snapshot = true

  lifecycle {
    ignore_changes = [
      # La contraseña la gestiona Secrets Manager, no Terraform. La API no la
      # devuelve al importar, así que declararla provocaría un diff permanente
      # y, peor, la dejaría en el state.
      password,
      # No reiniciar la instancia por una versión menor que AWS ya aplicó.
      engine_version,
    ]
  }
}
