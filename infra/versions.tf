terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }

  # Partial configuration: the state bucket comes from infra/bootstrap (see infra/README.md):
  #   terraform init -backend-config="bucket=<state bucket>" -backend-config="region=<region>"
  # Each workspace (dev, prod) keeps its own state under env:/<workspace>/.
  backend "s3" {
    key          = "resume-tailor/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "resume-tailor"
      Environment = terraform.workspace
      ManagedBy   = "terraform"
    }
  }
}

# CloudFront only accepts certificates from us-east-1.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "resume-tailor"
      Environment = terraform.workspace
      ManagedBy   = "terraform"
    }
  }
}
