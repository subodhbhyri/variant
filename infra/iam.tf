data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# --- The role ECS itself uses to start a task: pull the image, ship logs, read the secrets named in the task ---

resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_secretsmanager_secret.anthropic.arn,
      aws_secretsmanager_secret.google.arn,
      aws_db_instance.main.master_user_secret[0].secret_arn,
    ]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "read-the-task-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# --- What the running code may do ---------------------------------------------------------------------------

data "aws_iam_policy_document" "files_access" {
  statement {
    sid       = "ListTheFilesBucket"
    actions   = ["s3:ListBucket", "s3:ListBucketVersions", "s3:GetBucketLocation"]
    resources = [aws_s3_bucket.files.arn]
  }
  statement {
    sid = "UserFiles"
    actions = [
      "s3:GetObject", "s3:GetObjectVersion", "s3:PutObject",
      "s3:DeleteObject", "s3:DeleteObjectVersion",
    ]
    resources = ["${aws_s3_bucket.files.arn}/*"]
  }
}

# api: files (it issues the short-lived links) and the sign-in emails.
resource "aws_iam_role" "api" {
  name               = "${local.name}-api"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "api_files" {
  name   = "files"
  role   = aws_iam_role.api.id
  policy = data.aws_iam_policy_document.files_access.json
}

data "aws_iam_policy_document" "api_ses" {
  statement {
    actions   = ["ses:SendEmail"]
    resources = [aws_sesv2_email_identity.domain.arn]
  }
}

resource "aws_iam_role_policy" "api_ses" {
  name   = "send-sign-in-emails"
  role   = aws_iam_role.api.id
  policy = data.aws_iam_policy_document.api_ses.json
}

# worker: files. Its Anthropic key and database password arrive as secrets through the execution role.
resource "aws_iam_role" "worker" {
  name               = "${local.name}-worker"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "worker_files" {
  name   = "files"
  role   = aws_iam_role.worker.id
  policy = data.aws_iam_policy_document.files_access.json
}

# renderer: nothing. It has no AWS permissions at all.
resource "aws_iam_role" "renderer" {
  name               = "${local.name}-renderer"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}
