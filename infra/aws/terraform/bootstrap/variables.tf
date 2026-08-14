variable "aws_region" {
  description = "AWS Region used for account bootstrap resources."
  type        = string
  default     = "ap-south-1"
}

variable "project_name" {
  description = "Project name applied to supported AWS resource tags."
  type        = string
  default     = "adept"
}

variable "budget_notification_email" {
  description = "Email address that receives AWS cost-budget alerts."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.budget_notification_email))
    error_message = "budget_notification_email must be a valid email address."
  }
}

variable "monthly_budget_limit_usd" {
  description = "Monthly gross-cost budget limit in US dollars."
  type        = number
  default     = 100

  validation {
    condition     = var.monthly_budget_limit_usd >= 90
    error_message = "monthly_budget_limit_usd must be at least 90 to include every configured alert threshold."
  }
}
