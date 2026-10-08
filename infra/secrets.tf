# The secrets' VALUES are never in Terraform: it creates the containers with a placeholder, and the real value is
# put there once, by hand, with `aws secretsmanager put-secret-value` (infra/README.md). Terraform then ignores it.

resource "aws_secretsmanager_secret" "anthropic" {
  name                    = "${local.name}/anthropic-api-key"
  description             = "ANTHROPIC_API_KEY for the worker (the only place the Anthropic API is called from)"
  recovery_window_in_days = local.size.secret_recovery_days
}

resource "aws_secretsmanager_secret_version" "anthropic" {
  secret_id     = aws_secretsmanager_secret.anthropic.id
  secret_string = "REPLACE-ME-with-aws-secretsmanager-put-secret-value"
  lifecycle { ignore_changes = [secret_string] }
}

resource "aws_secretsmanager_secret" "google" {
  name                    = "${local.name}/google-oauth-client-secret"
  description             = "OAuth client secret for Sign in with Google"
  recovery_window_in_days = local.size.secret_recovery_days
}

resource "aws_secretsmanager_secret_version" "google" {
  secret_id     = aws_secretsmanager_secret.google.id
  secret_string = "REPLACE-ME-with-aws-secretsmanager-put-secret-value"
  lifecycle { ignore_changes = [secret_string] }
}
