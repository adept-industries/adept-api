resource "grafana_message_template" "adept" {
  name = "adept-notifications"

  template = <<-EOT
    {{ define "adept.title" -}}
    [{{ .Status }}] Adept: {{ len .Alerts.Firing }} firing, {{ len .Alerts.Resolved }} recovered
    {{- end }}

    {{ define "adept.message" -}}
    Status: {{ .Status }}

    {{- if gt (len .Alerts.Firing) 0 }}
    FIRING
    {{- range .Alerts.Firing }}
    - {{ .Labels.alertname }} ({{ .Labels.severity }}, {{ .Labels.service }}): {{ .Annotations.summary }}
    {{- end }}
    {{- end }}

    {{- if gt (len .Alerts.Resolved) 0 }}
    RECOVERED
    {{- range .Alerts.Resolved }}
    - {{ .Labels.alertname }} ({{ .Labels.severity }}, {{ .Labels.service }}): {{ .Annotations.summary }}
    {{- end }}
    {{- end }}

    No Data and query-error alerts are intentionally delivered as firing alerts; disappearing telemetry is not a recovery signal.
    {{- end }}
  EOT
}

resource "grafana_contact_point" "adept_email" {
  name = "Adept owner email"

  email {
    addresses               = var.notification_emails
    disable_resolve_message = false
    single_email            = true
    subject                 = "{{ template \"adept.title\" . }}"
    message                 = "{{ template \"adept.message\" . }}"
  }

  depends_on = [grafana_message_template.adept]
}
