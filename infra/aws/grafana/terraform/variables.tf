variable "grafana_url" {
  description = "Grafana Cloud stack URL, for example https://example.grafana.net."
  type        = string

  validation {
    condition     = startswith(var.grafana_url, "https://")
    error_message = "grafana_url must use HTTPS."
  }
}

variable "grafana_auth" {
  description = "Stack-scoped Grafana service-account token with alert provisioning access. Supply with TF_VAR_grafana_auth; this is not the Alloy ingestion token."
  type        = string
  sensitive   = true
}

variable "prometheus_datasource_uid" {
  description = "UID of the Grafana Cloud Prometheus datasource queried by alert rules."
  type        = string

  validation {
    condition     = length(trimspace(var.prometheus_datasource_uid)) > 0
    error_message = "prometheus_datasource_uid must not be empty."
  }
}

variable "notification_emails" {
  description = "Owner-controlled destinations for Grafana-side alert and recovery email."
  type        = list(string)

  validation {
    condition     = length(var.notification_emails) > 0 && alltrue([for address in var.notification_emails : length(trimspace(address)) > 3])
    error_message = "Provide at least one non-empty notification email address."
  }
}

variable "environment" {
  description = "Stable environment label attached to Adept alerts and used in metric selectors."
  type        = string
  default     = "production"
}

variable "website_probe_job" {
  description = "job label exported by the external website content-check."
  type        = string
  default     = "adept-website"
}

variable "api_probe_job" {
  description = "job label exported by the external API readiness content-check."
  type        = string
  default     = "adept-api-readiness"
}

variable "alerts_paused" {
  description = "Keep operational rules paused until their queries and notification route have been previewed in the target stack."
  type        = bool
  default     = true
}

variable "manage_notification_policy" {
  description = "Opt in only after reviewing the stack's existing policy tree; Grafana's resource replaces the complete tree."
  type        = bool
  default     = false
}

variable "notification_test_enabled" {
  description = "Create the controlled vector-based notification test rule. Leave false during normal operation."
  type        = bool
  default     = false
}

variable "notification_test_firing" {
  description = "When the notification test is enabled, true fires it and false recovers it."
  type        = bool
  default     = false
}
