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
}
