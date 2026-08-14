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
# Grupo de seguridad de la base de datos — HALLAZGO 1
#
# Grupo dedicado, creado el 2026-08-13 para sacar a RDS del grupo `default` de
# la VPC. Sigue el mismo patrón que bible-ecs-sg: el puerto se abre únicamente
# al grupo que necesita hablar con él, sin CIDR abierto.
#
# Sustituye a la regla `5432 desde 0.0.0.0/0` que tenía el grupo `default`, que
# es lo que exponía la base de datos a todo internet.
# ---------------------------------------------------------------------------

resource "aws_security_group" "rds" {
  name        = "bible-rds-sg"
  description = "Security group for Bible RDS instance"
  vpc_id      = data.aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_ecs" {
  security_group_id            = aws_security_group.rds.id
  description                  = "PostgreSQL solo desde las tasks de ECS"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.ecs_tasks.id
}

# ---------------------------------------------------------------------------
# Grupo de seguridad por defecto de la VPC
#
# Hasta el 2026-08-13 este grupo admitía `5432 desde 0.0.0.0/0` y era el que
# usaba la instancia de RDS: ahí estaba el hallazgo 1. La regla se revocó y la
# base de datos se movió a bible-rds-sg (arriba), así que hoy el grupo queda
# solo con la regla `self` que trae AWS de fábrica.
#
# Se mantiene declarado para que Terraform conserve la propiedad del grupo y
# nadie vuelva a abrirlo desde la consola sin que `plan` lo detecte.
#
# CUIDADO: aws_default_security_group asume la propiedad del grupo por defecto y
# elimina toda regla que no esté declarada aquí. Verificar el plan antes de
# aplicar.
# ---------------------------------------------------------------------------

resource "aws_default_security_group" "default" {
  vpc_id = data.aws_vpc.main.id

  ingress {
    protocol  = "-1"
    from_port = 0
    to_port   = 0
    self      = true
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
