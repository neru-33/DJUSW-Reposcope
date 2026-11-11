# 원본 이벤트 버킷: 퍼블릭 접근 차단, SSE-S3 암호화, 버저닝 끔, 보관 기간 후 삭제.
#
# 이 버킷에는 배포 번들(deploy/)도 들어갈 수 있어서 수명주기를 버킷 전체에 걸면 번들도 함께 지워진다.
# 원본 이벤트 키는 `날짜/delivery_id.json`이라 공통 접두사가 없으므로, Lambda가 붙이는 태그 kind=raw-event로 대상을 한정한다.
resource "aws_s3_bucket" "raw" {
  bucket        = var.raw_bucket_name
  force_destroy = var.force_destroy
}

resource "aws_s3_bucket_public_access_block" "raw" {
  bucket                  = aws_s3_bucket.raw.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "raw" {
  bucket = aws_s3_bucket.raw.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "raw" {
  bucket = aws_s3_bucket.raw.id

  rule {
    id     = "expire-raw-events"
    status = "Enabled"
    filter {
      tag {
        key   = "kind"
        value = "raw-event"
      }
    }
    expiration {
      days = var.raw_retention_days
    }
  }

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

data "aws_iam_policy_document" "raw_bucket" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.raw.arn,
      "${aws_s3_bucket.raw.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "raw" {
  bucket = aws_s3_bucket.raw.id
  policy = data.aws_iam_policy_document.raw_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.raw]
}
