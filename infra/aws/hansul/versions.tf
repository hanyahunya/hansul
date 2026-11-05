terraform {
  required_version = ">= 1.10.0"
  backend "s3" {}
  required_providers {
    aws  = { source = "hashicorp/aws", version = "~> 6.0" }
    http = { source = "hashicorp/http", version = "~> 3.0" }
  }
}

provider "aws" {
  region              = var.region
  profile             = var.profile
  allowed_account_ids = [var.account_id]
  default_tags { tags = { Project = "hansul", Component = "app" } }
}
