# ---------------------------------------------------------------------------
# Base de datos — HALLAZGO 1, CERRADO EL 2026-08-13
#
# Hasta esa fecha la instancia aceptaba conexiones desde cualquier host de
# internet, porque se combinaban tres condiciones:
#
#   1. publicly_accessible = true
#   2. vive en el subnet group por defecto, cuyas subredes son públicas
#      (tabla de rutas principal con 0.0.0.0/0 -> internet gateway)
#   3. su grupo de seguridad era el `default` de la VPC, que permitía
#      5432 desde 0.0.0.0/0
#
# Se corrigieron 1 y 3, en ese orden inverso: primero el grupo de seguridad
# —que es el paso que realmente elimina la exposición— y después la IP pública.
# La condición 2 sigue vigente: la instancia continúa en subredes públicas, pero
# ya sin IP pública ni regla abierta, así que no es alcanzable desde fuera.
# Moverla a subredes privadas con un subnet group propio queda pendiente como
# defensa en profundidad.
#
# Quitar la IP pública ahorra además el cargo de IPv4 pública ($0.005/h, unos
# 3,65 USD/mes): la misma acción cierra el hallazgo y baja la factura.
#
# OJO: al no ser publicly_accessible, la instancia ya NO es accesible desde un
# portátil fuera de la VPC. Para administrarla hace falta port forwarding por
# SSM o un bastion. La task de ECS no se ve afectada: está en la misma VPC y el
# DNS de RDS le resuelve a la IP privada.
#
# También aplica el hallazgo 3: single-AZ, 1 día de retención de backups y sin
# protección contra borrado.
#
# APAGADO NOCTURNO: esta instancia la para y la arranca EventBridge Scheduler
# (ver scheduler.tf). Terraform no gestiona su estado de ejecución.
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
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false # HALLAZGO 1 cerrado el 2026-08-13
  network_type           = "IPV4"
  availability_zone      = "mx-central-1a"
  multi_az               = false # HALLAZGO 3

  parameter_group_name       = "default.postgres17"
  ca_cert_identifier         = "rds-ca-rsa2048-g1"
  auto_minor_version_upgrade = true
  maintenance_window         = "thu:20:47-thu:21:17"
  backup_window              = "22:32-23:02"
  backup_retention_period    = 1     # HALLAZGO 3
  deletion_protection        = false # HALLAZGO 3
  copy_tags_to_snapshot      = true
  apply_immediately          = false

  # Performance Insights solo es gratis con 7 días de retención; a partir de ahí
  # se factura por vCPU. Se declara el 7 explícitamente para que un cambio a 731
  # desde la consola aparezca en `plan` en vez de aparecer en la factura.
  performance_insights_enabled          = true
  performance_insights_retention_period = 7

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
