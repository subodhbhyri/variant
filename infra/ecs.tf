resource "aws_ecs_cluster" "main" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

# The worker finds the renderer by name: renderer.<name>.internal, resolved inside the VPC only.
resource "aws_service_discovery_private_dns_namespace" "internal" {
  name = "${local.name}.internal"
  vpc  = aws_vpc.main.id
}

resource "aws_service_discovery_service" "renderer" {
  name = "renderer"

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.internal.id
    routing_policy = "MULTIVALUE"
    dns_records {
      ttl  = 10
      type = "A"
    }
  }

  health_check_custom_config {}
}

locals {
  db_secret_arn = aws_db_instance.main.master_user_secret[0].secret_arn

  # Settings and secrets shared by the api and the worker.
  app_environment = [
    { name = "DATABASE_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/tailor?sslmode=require" },
    { name = "S3_BUCKET", value = aws_s3_bucket.files.bucket },
    { name = "S3_SSE", value = "AES256" },
    { name = "AWS_REGION", value = var.aws_region },
    { name = "APP_ENV", value = local.env == "prod" ? "prod" : "dev" },
    { name = "APP_PUBLIC_BASE_URL", value = "https://${local.api_host}" },
    { name = "APP_FRONTEND_URL", value = "https://${local.app_host}" },
    { name = "APP_COOKIE_SECURE", value = "true" },
    { name = "APP_COOKIE_DOMAIN", value = var.domain_name },
    { name = "APP_MAIL_MODE", value = local.size.mail_mode },
    { name = "APP_MAIL_FROM", value = "noreply@${var.domain_name}" },
    { name = "GOOGLE_CLIENT_ID", value = var.google_client_id },
    { name = "RENDERER_URL", value = "http://renderer.${aws_service_discovery_private_dns_namespace.internal.name}:8090" },
  ]

  app_secrets = [
    { name = "DATABASE_USER", valueFrom = "${local.db_secret_arn}:username::" },
    { name = "DATABASE_PASSWORD", valueFrom = "${local.db_secret_arn}:password::" },
    { name = "GOOGLE_CLIENT_SECRET", valueFrom = aws_secretsmanager_secret.google.arn },
  ]

  # Every container runs with a read-only root; the only writable places are the volumes mounted below.
  log_config = { for k in ["api", "worker", "renderer"] : k => {
    logDriver = "awslogs"
    options = {
      awslogs-group         = aws_cloudwatch_log_group.service[k].name
      awslogs-region        = var.aws_region
      awslogs-stream-prefix = k
    }
  } }
}

# --- api ---------------------------------------------------------------------------------------------------

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = local.size.api_cpu
  memory                   = local.size.api_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.api.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  volume { name = "tmp" }

  container_definitions = jsonencode([{
    name                   = "api"
    image                  = "${aws_ecr_repository.web.repository_url}:${var.image_tag}"
    essential              = true
    user                   = "10001"
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = 8080, protocol = "tcp" }]
    environment = concat(local.app_environment, [
      { name = "APP_ROLE", value = "api" },
      { name = "APP_TLS", value = "true" }, # the load balancer reaches the tasks over HTTPS
    ])
    secrets          = local.app_secrets
    mountPoints      = [{ sourceVolume = "tmp", containerPath = "/tmp" }]
    logConfiguration = local.log_config["api"]
    linuxParameters  = { capabilities = { drop = ["ALL"] } }
  }])
}

resource "aws_ecs_service" "api" {
  name                              = "api"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.api.arn
  desired_count                     = local.size.api_count
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 120

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.api.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  depends_on = [aws_lb_listener.https]
}

# --- worker (the only service that holds the Anthropic key and may call the renderer) -----------------------

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.name}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = local.size.worker_cpu
  memory                   = local.size.worker_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.worker.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  volume { name = "tmp" }

  container_definitions = jsonencode([{
    name                   = "worker"
    image                  = "${aws_ecr_repository.web.repository_url}:${var.image_tag}"
    essential              = true
    user                   = "10001"
    readonlyRootFilesystem = true
    environment            = concat(local.app_environment, [{ name = "APP_ROLE", value = "worker" }])
    secrets = concat(local.app_secrets, [
      { name = "ANTHROPIC_API_KEY", valueFrom = aws_secretsmanager_secret.anthropic.arn },
    ])
    mountPoints      = [{ sourceVolume = "tmp", containerPath = "/tmp" }]
    logConfiguration = local.log_config["worker"]
    linuxParameters  = { capabilities = { drop = ["ALL"] } }
  }])
}

resource "aws_ecs_service" "worker" {
  name            = "worker"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = local.size.worker_count
  launch_type     = "FARGATE"

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.worker.id]
  }
}

# --- renderer (isolated subnets: no route to the internet) -------------------------------------------------

resource "aws_ecs_task_definition" "renderer" {
  family                   = "${local.name}-renderer"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = local.size.renderer_cpu
  memory                   = local.size.renderer_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.renderer.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  # Fargate has no tmpfs; these are empty per-task volumes over the image's open /scratch and /tmp.
  volume { name = "scratch" }
  volume { name = "tmp" }

  container_definitions = jsonencode([{
    name                   = "renderer"
    image                  = "${aws_ecr_repository.renderer.repository_url}:${var.image_tag}"
    essential              = true
    user                   = "10001"
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = 8090, protocol = "tcp" }]
    environment = [
      { name = "POOL_SIZE", value = tostring(local.size.renderer_pool) },
    ]
    mountPoints = [
      { sourceVolume = "scratch", containerPath = "/scratch" },
      { sourceVolume = "tmp", containerPath = "/tmp" },
    ]
    logConfiguration = local.log_config["renderer"]
    linuxParameters  = { capabilities = { drop = ["ALL"] } }
  }])
}

resource "aws_ecs_service" "renderer" {
  name            = "renderer"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.renderer.arn
  desired_count   = local.size.renderer_count
  launch_type     = "FARGATE"

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets         = aws_subnet.isolated[*].id
    security_groups = [aws_security_group.renderer.id]
  }

  service_registries {
    registry_arn = aws_service_discovery_service.renderer.arn
  }

  depends_on = [aws_vpc_endpoint.interface, aws_vpc_endpoint.s3]
}
