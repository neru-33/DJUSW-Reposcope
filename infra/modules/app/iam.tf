# EC2 역할: SQS 수신·삭제, 원본 버킷 읽기, Parameter Store 비밀값 읽기(webhook-secret 제외), Session Manager 접속.
#
# Session Manager 관리형 정책(AmazonSSMManagedInstanceCore)은 쓰지 않고 필요한 동작만 직접 허용한다.
# 관리형 정책에는 ssm:GetParameter(*)가 들어 있어 webhook-secret까지 읽을 수 있기 때문이다.

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${var.name_prefix}-app-role"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

resource "aws_iam_instance_profile" "app" {
  name = "${var.name_prefix}-app"
  role = aws_iam_role.app.name
}

data "aws_iam_policy_document" "app" {
  statement {
    sid = "ConsumeQueue"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes",
      "sqs:GetQueueUrl",
    ]
    resources = [var.queue_arn]
  }

  statement {
    sid       = "DlqStatus"
    actions   = ["sqs:GetQueueAttributes", "sqs:GetQueueUrl"]
    resources = [var.dlq_arn]
  }

  statement {
    sid       = "ReadRawEvents"
    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::${var.raw_bucket_name}/*"]
  }

  statement {
    sid       = "ReadAppSecrets"
    actions   = ["ssm:GetParameter", "ssm:GetParameters"]
    resources = var.secret_param_arns
  }

  statement {
    sid       = "NeverReadWebhookSecret"
    effect    = "Deny"
    actions   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath", "ssm:GetParameterHistory"]
    resources = [var.webhook_secret_param_arn]
  }

  statement {
    sid = "SessionManager"
    actions = [
      "ssm:UpdateInstanceInformation",
      "ssm:ListInstanceAssociations",
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
      "ec2messages:AcknowledgeMessage",
      "ec2messages:DeleteMessage",
      "ec2messages:FailMessage",
      "ec2messages:GetEndpoint",
      "ec2messages:GetMessages",
      "ec2messages:SendReply",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "app" {
  name   = "${var.name_prefix}-app-policy"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.app.json
}
