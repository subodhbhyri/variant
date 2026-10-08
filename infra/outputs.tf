output "app_url" {
  value = "https://${local.app_host}"
}

output "api_url" {
  value = "https://${local.api_host}"
}

output "ecr_web_repository" {
  value = aws_ecr_repository.web.repository_url
}

output "ecr_renderer_repository" {
  value = aws_ecr_repository.renderer.repository_url
}

output "anthropic_secret_arn" {
  value = aws_secretsmanager_secret.anthropic.arn
}

output "google_secret_arn" {
  value = aws_secretsmanager_secret.google.arn
}

output "spa_bucket" {
  value = aws_s3_bucket.spa.bucket
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.spa.id
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "api_log_group" {
  value = aws_cloudwatch_log_group.service["api"].name
}
