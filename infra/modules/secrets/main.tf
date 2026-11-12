# Parameter Store SecureString의 껍데기만 만든다.
#
# 값은 write-only 속성(value_wo)으로 초기값 CHANGE_ME만 넣는다. write-only 값은 state에 저장되지 않는다.
# 실제 값은 Terraform 밖에서 `aws ssm put-parameter --overwrite`로 입력하며, Terraform은 이 값을 읽지도 바꾸지도 않는다.
# 표준 티어와 기본 키(aws/ssm)를 쓰므로 추가 요금이 없다. Secrets Manager와 고객 관리형 KMS 키는 쓰지 않는다.
#
# 파라미터를 추가하려면 params에 항목 하나를 더한다.

locals {
  params = {
    "webhook-secret"       = "GitHub webhook HMAC secret (ingest Lambda)"
    "github-app-key"       = "GitHub App private key PEM (worker)"
    "github-client-secret" = "GitHub App OAuth client secret (API)"
    "db-password"          = "RDS master password (API, worker)"
  }
}

resource "aws_ssm_parameter" "this" {
  for_each = local.params

  name        = "/${var.name_prefix}/${each.key}"
  description = each.value
  type        = "SecureString"
  tier        = "Standard"

  value_wo         = "CHANGE_ME"
  value_wo_version = 1
}
