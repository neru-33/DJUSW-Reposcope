# EC2 1대: t3.small, 루트 20GB gp3, 퍼블릭 서브넷, 고정 IP(EIP) 1개, 상세 모니터링 끔.
# 최초 부팅 때는 Docker와 Compose만 설치한다. 컨테이너 배포 방식은 배포 이슈에서 정한다.
#
#   재생성: terraform apply -replace=module.app.aws_instance.app

data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_instance" "app" {
  ami                         = data.aws_ssm_parameter.al2023.insecure_value
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [var.security_group_id]
  iam_instance_profile        = aws_iam_instance_profile.app.name
  associate_public_ip_address = false # 퍼블릭 IP는 EIP 1개만 쓴다
  monitoring                  = false # 상세 모니터링 요금 방지

  # t3 기본값 unlimited는 CPU 크레딧 초과분이 과금된다
  credit_specification {
    cpu_credits = "standard"
  }

  # IMDSv2만 허용한다. hop limit 2는 worker 컨테이너가 EC2 역할의 자격 증명을 받을 수 있게 한다.
  # 이 역할에는 GitHub App 키를 읽는 권한이 있으므로 api와 Caddy 컨테이너는 받으면 안 된다.
  # 두 컨테이너의 IMDS 접근은 배포 설정에서 막는다.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size           = 20
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    compose_version = var.compose_version
    name_prefix     = var.name_prefix
  })
  user_data_replace_on_change = true

  tags = { Name = "${var.name_prefix}-app" }

  lifecycle {
    # 새 AMI가 나올 때마다 교체되지 않게 한다. 최신 AMI로 바꾸려면 -replace를 쓴다.
    # associate_public_ip_address는 만들 때만 쓰는 값이다. EIP가 붙으면 AWS가 true로 돌려줘서
    # plan마다 '교체'로 나오므로 이후 차이는 무시한다.
    ignore_changes = [ami, associate_public_ip_address]
  }
}

resource "aws_eip" "app" {
  domain   = "vpc"
  instance = aws_instance.app.id
  tags     = { Name = "${var.name_prefix}-app" }
}
