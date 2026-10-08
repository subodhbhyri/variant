# Images are pushed by scripts/deploy-images.ps1. Tags are immutable: every deployment names the image it runs.

resource "aws_ecr_repository" "web" {
  name                 = "${local.name}-web"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = local.env != "prod"

  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_repository" "renderer" {
  name                 = "${local.name}-renderer"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = local.env != "prod"

  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "keep_recent" {
  for_each   = { web = aws_ecr_repository.web.name, renderer = aws_ecr_repository.renderer.name }
  repository = each.value
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the 30 most recent images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 30 }
      action       = { type = "expire" }
    }]
  })
}
