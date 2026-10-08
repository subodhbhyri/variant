data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  # Only the dev and prod workspaces exist; any other workspace fails here, at plan time.
  env  = terraform.workspace
  name = "tailor-${local.env}"

  # Sizes per environment (PHASE6_SPEC.md section 10: api 2 tasks minimum, worker 1-2, renderer 2).
  sizes = {
    dev = {
      api_count            = 1
      api_cpu              = 512
      api_memory           = 1024
      worker_count         = 1
      worker_cpu           = 1024
      worker_memory        = 3072
      renderer_count       = 1
      renderer_cpu         = 1024
      renderer_memory      = 2048
      renderer_pool        = 1
      nat_gateways         = 1
      endpoint_azs         = 1
      db_class             = "db.t4g.micro"
      db_storage_gb        = 20
      db_multi_az          = false
      db_backup_days       = 1
      db_deletion_protect  = false
      log_retention_days   = 7
      secret_recovery_days = 0
      mail_mode            = "log" # sign-in links in the log, so scripts/smoke-test.ps1 can sign in; prod uses SES
    }
    prod = {
      api_count            = 2
      api_cpu              = 1024
      api_memory           = 2048
      worker_count         = 2
      worker_cpu           = 2048
      worker_memory        = 4096
      renderer_count       = 2
      renderer_cpu         = 2048
      renderer_memory      = 4096
      renderer_pool        = 2
      nat_gateways         = 2
      endpoint_azs         = 2
      db_class             = "db.t4g.small"
      db_storage_gb        = 50
      db_multi_az          = true
      db_backup_days       = 7
      db_deletion_protect  = true
      log_retention_days   = 30
      secret_recovery_days = 7
      mail_mode            = "ses"
    }
  }
  size = local.sizes[local.env]

  azs = slice(data.aws_availability_zones.available.names, 0, 2)

  # dev is served from dev-app.<domain> and dev-api.<domain>; prod from app.<domain> and api.<domain>.
  host_prefix = local.env == "prod" ? "" : "${local.env}-"
  app_host    = "${local.host_prefix}${var.app_subdomain}.${var.domain_name}"
  api_host    = "${local.host_prefix}${var.api_subdomain}.${var.domain_name}"

  account_id = data.aws_caller_identity.current.account_id
}
