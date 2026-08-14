output "state_bucket_name" {
  description = "S3 bucket used by environment Terraform backends."
  value       = aws_s3_bucket.terraform_state.id
}

output "state_bucket_region" {
  description = "AWS Region containing the Terraform state bucket."
  value       = var.aws_region
}

output "budget_name" {
  description = "AWS monthly gross-cost budget name."
  value       = aws_budgets_budget.monthly_gross_cost.name
}
