# 예산 2개(둘 다 무료 범위): 0.01달러만 넘어도 알리는 Zero-spend 예산과 월 예산.
#
# include_credit = false: 크레딧이 비용을 상쇄하면 순비용이 0으로 보이므로,
# 크레딧을 빼고 계산해야 크레딧이 소모되기 시작하는 순간에 알림이 온다.

locals {
  cost_types = {
    include_credit = false
    include_refund = false
  }
}

resource "aws_budgets_budget" "zero_spend" {
  name         = "${var.name_prefix}-zero-spend"
  budget_type  = "COST"
  limit_amount = "0.01"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_types {
    include_credit = local.cost_types.include_credit
    include_refund = local.cost_types.include_refund
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = var.alert_emails
  }
}

resource "aws_budgets_budget" "monthly" {
  name         = "${var.name_prefix}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_types {
    include_credit = local.cost_types.include_credit
    include_refund = local.cost_types.include_refund
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = var.alert_emails
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = var.alert_emails
  }
}
