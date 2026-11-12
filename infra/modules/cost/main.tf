# 예산 2개(둘 다 무료 범위). 두 예산은 크레딧을 다르게 계산해 역할을 나눈다.
#
# monthly: include_credit = false. 크레딧을 빼지 않은 사용량으로 계산한다.
#   크레딧이 있어도 사용량이 예산을 넘으면 알려서, 크레딧이 예상보다 빨리 줄어드는 것을 알 수 있다.
# zero_spend: include_credit = true. 크레딧을 뺀 실제 청구액으로 계산한다.
#   크레딧이 떨어지거나 크레딧으로 처리되지 않는 요금이 생겨 돈이 나갈 때만 알린다.

resource "aws_budgets_budget" "zero_spend" {
  name         = "${var.name_prefix}-zero-spend"
  budget_type  = "COST"
  limit_amount = "0.01"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_types {
    include_credit = true
    include_refund = false
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
    include_credit = false
    include_refund = false
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
