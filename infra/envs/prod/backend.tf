# state는 퍼블릭 접근을 차단하고 버전 관리를 켠 S3 버킷에 저장한다.
# 버킷 이름은 계정마다 다르므로 init 때 지정한다.
#   terraform init -backend-config="bucket=<state 버킷 이름>"
terraform {
  backend "s3" {
    key          = "prod/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}
