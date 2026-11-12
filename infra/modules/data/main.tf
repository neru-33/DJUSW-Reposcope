# RDS PostgreSQL: db.t4g.micro, gp3 20GB, 단일 AZ, 자동 백업 7일, 삭제 보호 켬, 퍼블릭 접근 끔.
#
# 비밀번호는 write-only 속성(password_wo)으로 넘겨 state에 남기지 않는다.
# 값은 Parameter Store의 db-password에 있고, 실행할 때 TF_VAR_db_password 환경 변수로 넣는다.
# 비밀번호를 바꾸려면 Parameter Store 값을 바꾼 뒤 password_version을 1 올려서 apply한다.

resource "aws_db_subnet_group" "this" {
  name       = var.name_prefix
  subnet_ids = var.subnet_ids
}

resource "aws_db_instance" "this" {
  identifier     = var.name_prefix
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = "db.t4g.micro"

  allocated_storage     = 20
  max_allocated_storage = 0 # 저장소 자동 확장을 끄고 20GB로 고정한다
  storage_type          = "gp3"
  storage_encrypted     = true # AWS 관리형 키(aws/rds)를 쓰므로 추가 요금이 없다

  db_name             = var.db_name
  username            = var.db_username
  password_wo         = var.db_password
  password_wo_version = var.password_version

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.security_group_id]
  availability_zone      = var.availability_zone
  multi_az               = false
  publicly_accessible    = false

  backup_retention_period = 7
  backup_window           = "18:00-18:30" # 한국 시간 03:00
  maintenance_window      = "sun:19:00-sun:19:30"
  copy_tags_to_snapshot   = true

  # 인프라를 종료할 때는 pg_dump로 따로 보관하므로 최종 스냅샷을 남기지 않는다(스냅샷 보관 요금 방지).
  skip_final_snapshot = true
  deletion_protection = var.deletion_protection

  performance_insights_enabled = false
  monitoring_interval          = 0
  auto_minor_version_upgrade   = true
  apply_immediately            = false

  lifecycle {
    precondition {
      condition     = var.db_password != null && var.db_password != "CHANGE_ME" && length(coalesce(var.db_password, "")) >= 16
      error_message = "db_password가 없거나 초기값(CHANGE_ME)이거나 16자 미만입니다. Parameter Store의 db-password에 16자 이상 값을 먼저 입력하고 TF_VAR_db_password로 넘기세요."
    }
  }
}
