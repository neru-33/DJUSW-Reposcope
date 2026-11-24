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

# CloudFront용 인증서는 us-east-1에 있어야 한다.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      project = var.name_prefix
    }
  }
}
