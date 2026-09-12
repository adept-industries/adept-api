# Expressions are shared with promtool tests; healthy rules return 0, unhealthy
# rules return 1, including an explicit missing-series result where appropriate.
locals {
  operational_alerts = {
    for name, definition in jsondecode(file("${path.module}/../alert-definitions.json")) :
    name => merge(definition, {
      expr = replace(replace(replace(definition.expr,
        "__ENV__", var.environment),
        "__WEBSITE_PROBE_JOB__", var.website_probe_job),
      "__API_PROBE_JOB__", var.api_probe_job)
    })
  }
  alert_definitions = merge(local.operational_alerts, var.notification_test_enabled ? {
    NotificationTest = {
      "for"       = "0s"
      service     = "test"
      severity    = "info"
      summary     = "Controlled Grafana notification delivery test"
      description = "Verify firing and recovery without stopping production services."
      expr        = var.notification_test_firing ? "vector(1)" : "vector(0)"
    }
  } : {})
}

resource "grafana_rule_group" "adept_operational_alerts" {
  name             = "adept-operational-alerts"
  folder_uid       = grafana_folder.adept.uid
  interval_seconds = 60

  dynamic "rule" {
    for_each = local.alert_definitions
    content {
      name           = rule.key
      for            = rule.value.for
      condition      = "B"
      no_data_state  = "Alerting"
      exec_err_state = "Alerting"
      is_paused      = rule.key == "NotificationTest" ? false : var.alerts_paused

      annotations = {
        summary     = rule.value.summary
        description = rule.value.description
      }
      labels = {
        environment = var.environment
        service     = rule.value.service
        severity    = rule.value.severity
      }

      # Route only these rules; never replace the stack's global policy tree.
      notification_settings {
        contact_point   = grafana_contact_point.adept_email.name
        group_by        = ["grafana_folder", "alertname", "environment", "service", "severity"]
        group_wait      = "1m"
        group_interval  = "10m"
        repeat_interval = "4h"
      }

      data {
        ref_id         = "A"
        datasource_uid = var.prometheus_datasource_uid
        relative_time_range {
          from = 300
          to   = 0
        }
        model = jsonencode({
          datasource = { type = "prometheus", uid = var.prometheus_datasource_uid }
          editorMode = "code"
          expr       = rule.value.expr
          instant    = true
          range      = false
          refId      = "A"
        })
      }

      data {
        ref_id         = "B"
        datasource_uid = "__expr__"
        relative_time_range {
          from = 300
          to   = 0
        }
        model = jsonencode({
          conditions = [{
            evaluator = { params = [0], type = "gt" }
            operator  = { type = "and" }
            query     = { params = ["A"] }
            reducer   = { params = [], type = "last" }
            type      = "query"
          }]
          datasource = { name = "Expression", type = "__expr__", uid = "__expr__" }
          expression = "A"
          refId      = "B"
          type       = "classic_conditions"
        })
      }
    }
  }
}
