terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      project = var.name_prefix
    }
  }
}

# 토큰은 환경 변수 CLOUDFLARE_API_TOKEN으로만 받는다. 코드와 tfvars에는 쓰지 않는다.
provider "cloudflare" {}
