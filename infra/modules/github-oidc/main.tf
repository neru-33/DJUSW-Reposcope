# GitHub Actions가 AWS에 들어오는 길: OIDC로 임시 자격 증명을 받는 역할 하나.
# 오래 쓰는 접근 키를 저장소 secret에 넣지 않는다.
#
# 역할은 var.github_repository의 var.github_branch 브랜치에서 실행된 워크플로만 맡을 수 있다.
# 2026-07-15 이후 만든 저장소의 OIDC 토큰은 sub에 소유자 ID와 저장소 ID가 들어간다(repo:<소유자>@<ID>/<이름>@<ID>:...).
# 다른 저장소, 다른 브랜치, 포크의 풀 리퀘스트는 맡지 못한다.
# 권한: ECR 이미지 올리기, 이 EC2 한 대에 배포 명령 보내기와 결과 읽기, 웹 버킷에 화면 올리기, 이 CloudFront 배포의 캐시 무효화.

data "aws_caller_identity" "current" {}

locals {
  create_provider = var.oidc_provider_arn == null
  provider_arn    = local.create_provider ? aws_iam_openid_connect_provider.github[0].arn : var.oidc_provider_arn
  repo_parts      = split("/", var.github_repository)
  subject         = "repo:${local.repo_parts[0]}@${var.github_owner_id}/${local.repo_parts[1]}@${var.github_repository_id}:ref:refs/heads/${var.github_branch}"
  instance_arn    = "arn:aws:ec2:${var.region}:${data.aws_caller_identity.current.account_id}:instance/${var.instance_id}"
  document_arn    = "arn:aws:ssm:${var.region}::document/AWS-RunShellScript"
}

# 계정에 하나만 만들 수 있다. GitHub 토큰은 AWS가 신뢰하는 인증 기관의 인증서로 검증하므로 지문(thumbprint)은 넣지 않는다.
resource "aws_iam_openid_connect_provider" "github" {
  count = local.create_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # StringLike가 아니라 StringEquals로 저장소와 브랜치를 정확히 맞춘다.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [local.subject]
    }
  }
}

resource "aws_iam_role" "deploy" {
  name                 = "${var.name_prefix}-github-deploy"
  assume_role_policy   = data.aws_iam_policy_document.assume.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "deploy" {
  # 로그인 토큰 발급은 리소스를 지정할 수 없다.
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
    ]
    resources = var.ecr_repository_arns
  }

  # 이 인스턴스 한 대와 셸 명령 실행 문서에만 보낼 수 있다.
  statement {
    sid       = "SendDeployCommand"
    actions   = ["ssm:SendCommand"]
    resources = [local.instance_arn, local.document_arn]
  }

  # 결과 조회는 리소스를 지정할 수 없다. 읽기만 한다.
  statement {
    sid       = "ReadCommandResult"
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
    resources = ["*"]
  }

  # 화면 파일 올리기(aws s3 sync --delete). 이 버킷 하나에만 쓸 수 있고 읽기 권한은 주지 않는다.
  statement {
    sid       = "WebBucketList"
    actions   = ["s3:ListBucket"]
    resources = [var.web_bucket_arn]
  }

  statement {
    sid       = "WebBucketWrite"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${var.web_bucket_arn}/*"]
  }

  # 이 배포 하나의 캐시만 무효화할 수 있다.
  statement {
    sid       = "InvalidateWebCache"
    actions   = ["cloudfront:CreateInvalidation"]
    resources = [var.cloudfront_distribution_arn]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "${var.name_prefix}-github-deploy-policy"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}
