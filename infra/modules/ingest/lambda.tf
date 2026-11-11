# 수신 Lambda: 256MB, 5초, 로그 7일, DB 접근 없음.
# 권한은 webhook secret 읽기, S3 원본 버킷 쓰기, SQS 전송만 준다. App 키, DB, S3 읽기 권한은 없다.

locals {
  function_name = "${var.name_prefix}-ingest"
}

data "archive_file" "ingest" {
  type        = "zip"
  source_dir  = var.lambda_source_dir
  output_path = var.lambda_zip_path
  excludes    = ["__pycache__"]
}

resource "aws_cloudwatch_log_group" "ingest" {
  name              = "/aws/lambda/${local.function_name}"
  retention_in_days = 7
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ingest" {
  name               = "${local.function_name}-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "ingest" {
  statement {
    sid       = "Logs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.ingest.arn}:*"]
  }

  statement {
    sid       = "ReadWebhookSecret"
    actions   = ["ssm:GetParameter"]
    resources = [var.webhook_secret_param_arn]
  }

  # 날짜 접두사(YYYY-MM-DD/) 아래에만 쓸 수 있게 해서 deploy/ 번들은 덮어쓰지 못하게 한다.
  statement {
    sid       = "WriteRawEvents"
    actions   = ["s3:PutObject", "s3:PutObjectTagging"]
    resources = ["${aws_s3_bucket.raw.arn}/20??-??-??/*"]
  }

  statement {
    sid       = "SendToQueue"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.events.arn]
  }
}

resource "aws_iam_role_policy" "ingest" {
  name   = "${local.function_name}-policy"
  role   = aws_iam_role.ingest.id
  policy = data.aws_iam_policy_document.ingest.json
}

resource "aws_lambda_function" "ingest" {
  function_name    = local.function_name
  role             = aws_iam_role.ingest.arn
  runtime          = var.lambda_runtime
  handler          = var.lambda_handler
  architectures    = ["arm64"]
  memory_size      = 256
  timeout          = 5
  filename         = data.archive_file.ingest.output_path
  source_code_hash = data.archive_file.ingest.output_base64sha256

  environment {
    variables = {
      WEBHOOK_SECRET_PARAM = var.webhook_secret_param_name
      RAW_BUCKET           = aws_s3_bucket.raw.bucket
      QUEUE_URL            = aws_sqs_queue.events.url
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.ingest,
    aws_iam_role_policy.ingest,
  ]
}
