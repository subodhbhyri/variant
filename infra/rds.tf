resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.isolated[*].id
}

resource "aws_db_parameter_group" "postgres" {
  name_prefix = "${local.name}-"
  family      = "postgres16"

  # TLS is required for every connection (PHASE6_SPEC.md section 9.3).
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  lifecycle { create_before_destroy = true }
}

resource "aws_db_instance" "main" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = "16"
  instance_class = local.size.db_class

  db_name  = "tailor"
  username = "tailor"
  # RDS creates and rotates the master password in Secrets Manager; no password is ever in Terraform state or code.
  manage_master_user_password = true

  allocated_storage     = local.size.db_storage_gb
  max_allocated_storage = local.size.db_storage_gb * 4
  storage_type          = "gp3"
  storage_encrypted     = true

  multi_az               = local.size.db_multi_az
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  parameter_group_name   = aws_db_parameter_group.postgres.name
  publicly_accessible    = false

  backup_retention_period    = local.size.db_backup_days
  backup_window              = "03:00-04:00"
  maintenance_window         = "sun:04:30-sun:05:30"
  auto_minor_version_upgrade = true
  copy_tags_to_snapshot      = true

  deletion_protection       = local.size.db_deletion_protect
  skip_final_snapshot       = local.env != "prod"
  final_snapshot_identifier = local.env == "prod" ? "${local.name}-final" : null
  apply_immediately         = local.env != "prod"
}
