# Terraform state를 저장할 S3 버킷을 만든다. 계정당 한 번만 실행한다.
#
# 이 설정은 state를 로컬 파일(terraform.tfstate)에 둔다. 버킷이 생기기 전에는 저장할 곳이 없기 때문이다.
# 그 파일은 git에 올리지 않는다. 잃어버려도 버킷은 그대로 남고, 다시 만들 일이 생기면 terraform import로 연결한다.

terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "name_prefix" {
  description = "버킷 이름 앞에 붙는 접두어"
  type        = string
  default     = "reposcope"
  # envs/prod의 name_prefix와 같은 값을 쓰므로 같은 규칙으로 검사한다.
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,19}$", var.name_prefix))
    error_message = "name_prefix는 소문자로 시작하고 소문자, 숫자, 하이픈만 쓰는 20자 이하 값이어야 합니다."
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

data "aws_caller_identity" "current" {}

locals {
  # 계정 번호를 붙여 전 세계에서 하나뿐인 이름을 만든다
  bucket_name = "${var.name_prefix}-tfstate-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "state" {
  bucket = local.bucket_name

  # state가 들어 있는 버킷이므로 실수로 지우지 못하게 한다
  lifecycle {
    prevent_destroy = true
  }
}

# 버저닝: state를 잘못 덮어써도 이전 버전으로 되돌릴 수 있다
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 오래된 버전은 90일 뒤 정리해 보관 요금이 쌓이지 않게 한다
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-old-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }

  depends_on = [aws_s3_bucket_versioning.state]
}

output "state_bucket_name" {
  description = "envs/prod를 init할 때 -backend-config=\"bucket=...\"에 넣을 값"
  value       = aws_s3_bucket.state.bucket
}
