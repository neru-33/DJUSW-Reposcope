data "aws_caller_identity" "current" {}

locals {
  # 계정 번호를 붙여 전 세계에서 하나뿐인 이름을 만든다(bootstrap의 state 버킷과 같은 방식)
  raw_bucket_name = coalesce(var.raw_bucket_name, "${var.name_prefix}-raw-events-${data.aws_caller_identity.current.account_id}")
}

module "network" {
  source = "../../modules/network"

  name_prefix     = var.name_prefix
  region          = var.region
  admin_ssh_cidrs = var.admin_ssh_cidrs
}

module "secrets" {
  source = "../../modules/secrets"

  name_prefix = var.name_prefix
}

module "data" {
  source = "../../modules/data"

  name_prefix         = var.name_prefix
  subnet_ids          = module.network.private_subnet_ids
  security_group_id   = module.network.db_security_group_id
  availability_zone   = module.network.availability_zone
  db_password         = var.db_password
  deletion_protection = var.rds_deletion_protection
  db_name             = replace(var.name_prefix, "-", "_")
  db_username         = replace(var.name_prefix, "-", "_")
}

module "ingest" {
  source = "../../modules/ingest"

  name_prefix               = var.name_prefix
  raw_bucket_name           = local.raw_bucket_name
  force_destroy             = var.raw_force_destroy
  webhook_secret_param_name = module.secrets.names["webhook-secret"]
  webhook_secret_param_arn  = module.secrets.arns["webhook-secret"]
  alert_email               = var.alert_email
  lambda_source_dir         = "${path.root}/../../../webhook"
  lambda_zip_path           = "${path.root}/.build/ingest.zip"
}

module "app" {
  source = "../../modules/app"

  name_prefix              = var.name_prefix
  region                   = var.region
  instance_type            = var.instance_type
  subnet_id                = module.network.public_subnet_id
  security_group_id        = module.network.app_security_group_id
  raw_bucket_name          = module.ingest.raw_bucket_name
  queue_arn                = module.ingest.queue_arn
  dlq_arn                  = module.ingest.dlq_arn
  webhook_secret_param_arn = module.secrets.arns["webhook-secret"]
  secret_param_arns = [
    module.secrets.arns["github-app-key"],
    module.secrets.arns["github-client-secret"],
    module.secrets.arns["db-password"],
  ]
}

module "cost" {
  source = "../../modules/cost"

  name_prefix        = var.name_prefix
  alert_emails       = [var.alert_email]
  monthly_budget_usd = var.monthly_budget_usd
}
