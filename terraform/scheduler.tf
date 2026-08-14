# ---------------------------------------------------------------------------
# Apagado nocturno — creado el 2026-08-13
#
# La aplicación tiene pocos usuarios de día y ninguno de noche, así que pagar
# Fargate y RDS 24/7 es tirar dinero. Estos schedules apagan ambos de la 01:00 a
# las ~06:55, hora de Ciudad de México: unas 6 horas al día, ~25% menos de horas
# facturadas en las dos partidas que sí se pueden apagar.
#
# LO QUE ESTO NO AHORRA: el ALB factura su tarifa base las 24 horas aunque no
# haya ni un target registrado, y el almacenamiento de RDS se factura también
# con la instancia parada. Ver docs/aws-architecture.md.
#
# EL ORDEN IMPORTA. Si RDS cae con la aplicación viva, HikariCP no puede abrir
# conexiones, /actuator/health empieza a fallar y el health check del contenedor
# mata la task en bucle. Por eso se apaga ECS primero y se levanta el último:
#
#   01:00  ECS -> 0        la aplicación se retira antes de tocar la base
#   01:10  RDS stop        10 min de margen para que la task termine
#   06:40  RDS start       arrancar RDS tarda entre 5 y 10 min
#   06:55  ECS -> 1        +15 min de margen; la app tarda ~64 s en arrancar
#
# A las 07:00 el servicio está listo, antes de que llegue nadie.
#
# Las ventanas de mantenimiento y backup de RDS (jue 20:47 UTC y 22:32 UTC, o
# sea 14:47 y 16:32 hora de México) caen de lleno en horario diurno, así que no
# chocan con esta ventana. Si alguna se mueve, revisar que siga siendo así: una
# instancia parada durante su ventana de mantenimiento aplica los cambios
# pendientes en el siguiente arranque, lo que alarga el start.
#
# RDS reinicia solo cualquier instancia que lleve 7 días parada. Aquí no aplica,
# porque se arranca cada mañana, pero conviene saberlo si se deshabilitan los
# schedules dejando la base parada.
# ---------------------------------------------------------------------------

resource "aws_iam_role" "scheduler" {
  name        = "bible-scheduler-role"
  description = "Rol que asume EventBridge Scheduler para el apagado nocturno"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "scheduler.amazonaws.com" }
        Action    = "sts:AssumeRole"
        Condition = {
          # Sin esto, cualquier schedule de la cuenta podría asumir este rol.
          StringEquals = { "aws:SourceAccount" = var.account_id }
        }
      }
    ]
  })
}

resource "aws_iam_role_policy" "scheduler" {
  name = "bible-scheduler-policy"
  role = aws_iam_role.scheduler.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EscalarElServicioDeECS"
        Effect   = "Allow"
        Action   = "ecs:UpdateService"
        Resource = aws_ecs_service.app.id
      },
      {
        Sid    = "PararYArrancarLaBaseDeDatos"
        Effect = "Allow"
        Action = [
          "rds:StopDBInstance",
          "rds:StartDBInstance",
        ]
        Resource = aws_db_instance.main.arn
      },
    ]
  })
}

# ---------------------------------------------------------------------------
# Schedules
#
# Usan "universal targets" (arn:aws:scheduler:::aws-sdk:<servicio>:<accion>),
# que llaman directamente a la API de AWS sin Lambda de por medio.
#
# El `input` va en el formato que espera el SDK, no el de la CLI. Los nombres de
# los campos son los de la API en PascalCase.
# ---------------------------------------------------------------------------

locals {
  # Mexico suprimió el horario de verano en 2022, así que esta zona es UTC-6
  # todo el año. Aun así se declara por nombre y no como offset fijo: si la
  # política volviera a cambiar, el schedule se ajusta solo.
  schedule_timezone = "America/Mexico_City"
}

resource "aws_scheduler_schedule" "ecs_stop" {
  name                         = "bible-ecs-stop"
  description                  = "Baja el servicio ECS a 0 tasks para la noche"
  schedule_expression          = "cron(0 1 * * ? *)"
  schedule_expression_timezone = local.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.scheduler.arn

    input = jsonencode({
      Cluster      = aws_ecs_cluster.main.name
      Service      = aws_ecs_service.app.name
      DesiredCount = 0
    })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}

resource "aws_scheduler_schedule" "rds_stop" {
  name                         = "bible-rds-stop"
  description                  = "Para la instancia de RDS, 10 min despues de retirar ECS"
  schedule_expression          = "cron(10 1 * * ? *)"
  schedule_expression_timezone = local.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:rds:stopDBInstance"
    role_arn = aws_iam_role.scheduler.arn

    input = jsonencode({
      DbInstanceIdentifier = aws_db_instance.main.identifier
    })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}

resource "aws_scheduler_schedule" "rds_start" {
  name                         = "bible-rds-start"
  description                  = "Arranca RDS; tarda entre 5 y 10 min en estar disponible"
  schedule_expression          = "cron(40 6 * * ? *)"
  schedule_expression_timezone = local.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:rds:startDBInstance"
    role_arn = aws_iam_role.scheduler.arn

    input = jsonencode({
      DbInstanceIdentifier = aws_db_instance.main.identifier
    })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}

resource "aws_scheduler_schedule" "ecs_start" {
  name                         = "bible-ecs-start"
  description                  = "Devuelve el servicio ECS a 1 task antes de las 07:00"
  schedule_expression          = "cron(55 6 * * ? *)"
  schedule_expression_timezone = local.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.scheduler.arn

    input = jsonencode({
      Cluster      = aws_ecs_cluster.main.name
      Service      = aws_ecs_service.app.name
      DesiredCount = 1
    })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}
