# Mock provider: plans only; no Grafana/AWS credentials or network calls.
mock_provider "grafana" {}

variables {
  grafana_url               = "https://example.grafana.net"
  grafana_auth              = "local-test-only"
  prometheus_datasource_uid = "test-prometheus"
  notification_emails       = ["test@example.invalid"]
}

run "paused_rules_are_safe_and_routed" {
  command = plan

  assert {
    condition = alltrue([
      for rule in grafana_rule_group.adept_operational_alerts.rule :
      rule.is_paused && rule.no_data_state == "Alerting" && rule.exec_err_state == "Alerting"
    ])
    error_message = "Real alerts must start paused and fail visibly on No Data/query errors."
  }

  assert {
    condition = alltrue([
      for rule in grafana_rule_group.adept_operational_alerts.rule :
      rule.notification_settings[0].contact_point == grafana_contact_point.adept_email.name &&
      rule.notification_settings[0].repeat_interval == "4h" &&
      jsondecode(rule.data[1].model).conditions[0].evaluator.type == "gt" &&
      jsondecode(rule.data[1].model).conditions[0].evaluator.params[0] == 0
    ])
    error_message = "Every rule must route to the owner and compare its boolean query with zero."
  }

  assert {
    condition = alltrue([
      for rule in grafana_rule_group.adept_operational_alerts.rule :
      jsondecode(rule.data[0].model).expr == local.operational_alerts[rule.name].expr &&
      jsondecode(rule.data[0].model).instant &&
      !jsondecode(rule.data[0].model).range
    ])
    error_message = "Grafana must use the same instant expressions tested by promtool."
  }

  assert {
    condition     = !one(grafana_contact_point.adept_email.email).disable_resolve_message
    error_message = "Recovery emails must remain enabled."
  }
}

run "notification_test_fires_without_enabling_production_rules" {
  command = plan
  variables {
    notification_test_enabled = true
    notification_test_firing  = true
  }
  assert {
    condition = alltrue([
      for rule in grafana_rule_group.adept_operational_alerts.rule :
      rule.name == "NotificationTest" ? (!rule.is_paused && jsondecode(rule.data[0].model).expr == "vector(1)") : rule.is_paused
    ])
    error_message = "Only the explicitly enabled synthetic notification test should fire."
  }
}

run "notification_test_recovers" {
  command = plan
  variables {
    notification_test_enabled = true
    notification_test_firing  = false
  }
  assert {
    condition     = local.alert_definitions.NotificationTest.expr == "vector(0)"
    error_message = "Notification recovery must evaluate zero, not disappear."
  }
}
