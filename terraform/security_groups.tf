# ---------------------------------------------------------------------------
# Grupo de seguridad del ALB
#
# Estado tras el hallazgo 2, fase 1: una única regla de entrada, restringida a
# los rangos de origen de CloudFront. Antes admitía 80 y 443 desde 0.0.0.0/0.
# ---------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  name        = "bible-alb-sg"
  description = "Security group for Bible ALB"
  vpc_id      = data.aws_vpc.main.id

}

resource "aws_vpc_security_group_ingress_rule" "alb_http_from_cloudfront" {
  security_group_id = aws_security_group.alb.id
  description       = "Solo origenes CloudFront"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront_origin_facing.id
}

resource "aws_vpc_security_group_egress_rule" "alb_all" {
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# ---------------------------------------------------------------------------
# Grupo de seguridad de las tasks
#
# Correctamente configurado: 8080 solo desde el ALB, sin CIDR abierto.
# Es el patrón que debería seguir también el de la base de datos.
# ---------------------------------------------------------------------------

resource "aws_security_group" "ecs_tasks" {
  name        = "bible-ecs-sg"
  description = "Security group for Bible ECS Tasks"
  vpc_id      = data.aws_vpc.main.id

}

resource "aws_vpc_security_group_ingress_rule" "ecs_from_alb" {
  security_group_id            = aws_security_group.ecs_tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.alb.id
}

resource "aws_vpc_security_group_egress_rule" "ecs_all" {
  security_group_id = aws_security_group.ecs_tasks.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# ---------------------------------------------------------------------------
# Grupo de seguridad por defecto de la VPC — HALLAZGO 1, SIN RESOLVER
#
# La instancia de RDS usa este grupo, que admite 5432 desde 0.0.0.0/0. Sumado a
# `publicly_accessible = true` y a que las subredes son públicas, la base de
# datos acepta conexiones desde cualquier host de internet.
#
# Se declara tal como está HOY para que `plan` salga limpio. La corrección es
# sustituir la regla abierta por una que referencie aws_security_group.ecs_tasks,
# idealmente moviendo RDS a un grupo dedicado. Ver docs/aws-architecture.md.
#
# CUIDADO: aws_default_security_group asume la propiedad del grupo por defecto y
# elimina toda regla que no esté declarada aquí. Verificar el plan antes de
# aplicar.
# ---------------------------------------------------------------------------

resource "aws_default_security_group" "default" {
  vpc_id = data.aws_vpc.main.id

  ingress {
    protocol    = "tcp"
    from_port   = 5432
    to_port     = 5432
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    protocol    = "-1"
    from_port   = 0
    to_port     = 0
    self        = true
  }

  egress {
    protocol    = "-1"
    from_port   = 0
    to_port     = 0
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ---------------------------------------------------------------------------
# Grupos huérfanos — ELIMINADOS el 2026-08-04
#
#   biblia-sg      sg-06179292d0aba63da
#   biblia-alb-sg  sg-097d84a75b2c197ef
#
# Ambos abrían 80/443 al mundo sin estar asociados a nada (hallazgo 7). Se
# borraron tras confirmar 0 interfaces de red y 0 referencias desde reglas de
# otros grupos.
#
# Los tres que quedan en la VPC —bible-alb-sg, bible-ecs-sg y el `default`— son
# los declarados arriba, todos en uso.
# ---------------------------------------------------------------------------
