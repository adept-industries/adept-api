provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Environment = "bootstrap"
      ManagedBy   = "Terraform"
      Project     = var.project_name
    }
  }
}
