# VPC, 서브넷, 인터넷 게이트웨이, 라우팅, 보안 그룹.
# NAT Gateway는 만들지 않는다. EC2는 퍼블릭 서브넷에서 인터넷 게이트웨이로 직접 나간다.
#
#   퍼블릭  10.0.1.0/24  (AZ a)  EC2
#   프라이빗 10.0.11.0/24 (AZ a)  RDS
#   프라이빗 10.0.12.0/24 (AZ c)  DB 서브넷 그룹의 2개 AZ 요건을 맞추기 위한 서브넷, 인스턴스 없음

locals {
  az_a = "${var.region}a"
  az_c = "${var.region}c"
}

resource "aws_vpc" "this" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = var.name_prefix }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = var.name_prefix }
}

resource "aws_subnet" "public_a" {
  vpc_id            = aws_vpc.this.id
  cidr_block        = "10.0.1.0/24"
  availability_zone = local.az_a

  # 서브넷은 IP를 자동으로 주지 않는다. EC2 자원이 associate_public_ip_address로 공인 IP를 받는다.
  map_public_ip_on_launch = false

  tags = { Name = "${var.name_prefix}-public-a" }
}

resource "aws_subnet" "private_a" {
  vpc_id            = aws_vpc.this.id
  cidr_block        = "10.0.11.0/24"
  availability_zone = local.az_a
  tags              = { Name = "${var.name_prefix}-private-a" }
}

resource "aws_subnet" "private_c" {
  vpc_id            = aws_vpc.this.id
  cidr_block        = "10.0.12.0/24"
  availability_zone = local.az_c
  tags              = { Name = "${var.name_prefix}-private-c" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = { Name = "${var.name_prefix}-public" }
}

resource "aws_route_table_association" "public_a" {
  subnet_id      = aws_subnet.public_a.id
  route_table_id = aws_route_table.public.id
}

# 프라이빗 서브넷은 VPC 기본 라우팅 테이블(VPC 내부 경로만)을 쓰므로 인터넷 경로가 없다.

# ---------------------------------------------------------------- 보안 그룹
# 보안 그룹 description은 영문 일부 기호만 허용하므로 영어로 쓴다.

resource "aws_security_group" "app" {
  name        = "${var.name_prefix}-app"
  description = "EC2 app. No inbound rules except optional SSH."
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name_prefix}-app" }
}

# SSH는 기본으로 닫아 두고 SSM Session Manager로 접속한다.
resource "aws_vpc_security_group_ingress_rule" "app_ssh_admin" {
  for_each = toset(var.admin_ssh_cidrs)

  security_group_id = aws_security_group.app.id
  description       = "SSH from admin IP"
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  cidr_ipv4         = each.value
}

resource "aws_vpc_security_group_egress_rule" "app_all" {
  security_group_id = aws_security_group.app.id
  description       = "Outbound (image registry, SQS, S3, SSM, GitHub API, RDS)"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "db" {
  name        = "${var.name_prefix}-db"
  description = "RDS: 5432 from EC2 security group only"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name_prefix}-db" }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from EC2"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.app.id
}
