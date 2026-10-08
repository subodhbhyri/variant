# Terraform removes the default allow-all egress rule from a new security group, so a group has only the rules
# written here. The renderer's group has NO rule to the internet: only to the VPC endpoints and S3 (which its
# task needs to start and log), and it accepts connections only from the worker.

resource "aws_security_group" "alb" {
  name_prefix = "${local.name}-alb-"
  description = "Public load balancer"
  vpc_id      = aws_vpc.main.id
  lifecycle { create_before_destroy = true }
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from anywhere"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP, only to redirect to HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_api" {
  security_group_id            = aws_security_group.alb.id
  description                  = "To the api tasks"
  referenced_security_group_id = aws_security_group.api.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "api" {
  name_prefix = "${local.name}-api-"
  description = "api tasks"
  vpc_id      = aws_vpc.main.id
  lifecycle { create_before_destroy = true }
}

resource "aws_vpc_security_group_ingress_rule" "api_from_alb" {
  security_group_id            = aws_security_group.api.id
  description                  = "HTTPS from the load balancer"
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

# The api talks to the database, S3, SES and Secrets Manager (through NAT and the S3 endpoint).
resource "aws_vpc_security_group_egress_rule" "api_out" {
  security_group_id = aws_security_group.api.id
  description       = "Outbound HTTPS and PostgreSQL"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "worker" {
  name_prefix = "${local.name}-worker-"
  description = "worker tasks"
  vpc_id      = aws_vpc.main.id
  lifecycle { create_before_destroy = true }
}

# The worker calls the Anthropic API (through NAT), the database, S3 and the renderer.
resource "aws_vpc_security_group_egress_rule" "worker_out" {
  security_group_id = aws_security_group.worker.id
  description       = "Outbound: Anthropic API, database, S3, renderer"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "renderer" {
  name_prefix = "${local.name}-renderer-"
  description = "LibreOffice renderer: reachable only from the worker, no route or rule to the internet"
  vpc_id      = aws_vpc.main.id
  lifecycle { create_before_destroy = true }
}

resource "aws_vpc_security_group_ingress_rule" "renderer_from_worker" {
  security_group_id            = aws_security_group.renderer.id
  description                  = "Render requests from the worker only"
  referenced_security_group_id = aws_security_group.worker.id
  from_port                    = 8090
  to_port                      = 8090
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "renderer_to_endpoints" {
  security_group_id            = aws_security_group.renderer.id
  description                  = "HTTPS to the ECR and CloudWatch Logs VPC endpoints (image pull, log shipping)"
  referenced_security_group_id = aws_security_group.endpoints.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
}

data "aws_prefix_list" "s3" {
  name = "com.amazonaws.${var.aws_region}.s3"
}

resource "aws_vpc_security_group_egress_rule" "renderer_to_s3" {
  security_group_id = aws_security_group.renderer.id
  description       = "HTTPS to S3 through the gateway endpoint (ECR image layers)"
  prefix_list_id    = data.aws_prefix_list.s3.id
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_security_group" "endpoints" {
  name_prefix = "${local.name}-endpoints-"
  description = "VPC interface endpoints"
  vpc_id      = aws_vpc.main.id
  lifecycle { create_before_destroy = true }
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_from_renderer" {
  security_group_id            = aws_security_group.endpoints.id
  description                  = "HTTPS from the renderer"
  referenced_security_group_id = aws_security_group.renderer.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "db" {
  name_prefix = "${local.name}-db-"
  description = "PostgreSQL"
  vpc_id      = aws_vpc.main.id
  lifecycle { create_before_destroy = true }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_api" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from the api"
  referenced_security_group_id = aws_security_group.api.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "db_from_worker" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from the worker"
  referenced_security_group_id = aws_security_group.worker.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}
