# ---------------------------------------------------------------------------
# Red preexistente
#
# Toda la infraestructura vive en la VPC por defecto de la cuenta, cuyas tres
# subredes usan la tabla de rutas principal con salida directa a un internet
# gateway: son subredes públicas. Terraform NO gestiona la VPC ni las subredes
# —son recursos de la cuenta, no del proyecto— pero sí las referencia.
#
# Esta es la raíz del hallazgo 1: RDS vive en estas subredes públicas. Cuando se
# corrija, aquí es donde aparecerán las subredes privadas nuevas.
# ---------------------------------------------------------------------------

data "aws_vpc" "main" {
  id = var.vpc_id
}

data "aws_subnet" "az_a" {
  id = "subnet-05d58e02a68c1526d" # mx-central-1a
}

data "aws_subnet" "az_b" {
  id = "subnet-0d422b51e4fa87fcd" # mx-central-1b
}

data "aws_subnet" "az_c" {
  id = "subnet-02c771c1f18b2308c" # mx-central-1c
}

locals {
  # El ALB está en las tres zonas; el servicio de ECS solo en 1a y 1b.
  alb_subnet_ids = [
    data.aws_subnet.az_a.id,
    data.aws_subnet.az_b.id,
    data.aws_subnet.az_c.id,
  ]

  ecs_subnet_ids = [
    data.aws_subnet.az_a.id,
    data.aws_subnet.az_b.id,
  ]
}

# Rangos de origen de CloudFront. Es una lista gestionada por AWS: sus entradas
# cambian solas cuando AWS añade edges, sin que haya que tocar nada aquí.
data "aws_ec2_managed_prefix_list" "cloudfront_origin_facing" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}
