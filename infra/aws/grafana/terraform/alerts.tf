resource "grafana_rule_group" "adept_operational_alerts" {
  name             = "adept-operational-alerts"
  folder_uid       = grafana_folder.adept.uid
  interval_seconds = 60

  rule {
    name           = "WebsiteDown"
    for            = "3m"
    condition      = "B"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "Adept public website endpoint probe is failing"
      description = "The external synthetic probe for the Adept website (${var.website_probe_job}) returned failure or unexpected body content."
    }

    labels = {
      environment = var.environment
      service     = "caddy"
      severity    = "critical"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "probe_success{job=\"${var.website_probe_job}\", environment=\"${var.environment}\"} == 0"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  rule {
    name           = "ApiReadinessDown"
    for            = "3m"
    condition      = "B"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "Adept API readiness probe is failing"
      description = "The external API readiness probe (${var.api_probe_job}) is failing or Actuator health readiness is reporting down."
    }

    labels = {
      environment = var.environment
      service     = "api"
      severity    = "critical"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "probe_success{job=\"${var.api_probe_job}\", environment=\"${var.environment}\"} == 0"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  rule {
    name           = "AlloyCollectorDown"
    for            = "5m"
    condition      = "B"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "Grafana Alloy collector telemetry is missing"
      description = "Alloy collector instance is unreachable or down on host."
    }

    labels = {
      environment = var.environment
      service     = "alloy"
      severity    = "warning"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "up{job=\"alloy\", environment=\"${var.environment}\"} == 0"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  rule {
    name           = "EngineWorkerCollectorDown"
    for            = "5m"
    condition      = "B"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "Engine Worker metrics scrape endpoint is unreachable"
      description = "Alloy cannot scrape the private metrics endpoint on adept-engine-worker:8001."
    }

    labels = {
      environment = var.environment
      service     = "engine-worker"
      severity    = "critical"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "up{job=\"adept-engine-worker\", environment=\"${var.environment}\"} == 0"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  rule {
    name           = "WorkerThreadsDegraded"
    for            = "5m"
    condition      = "B"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "Engine Worker live thread count is below configured count"
      description = "The number of live background worker threads is less than ENGINE_WORKER_THREADS for more than 5 minutes."
    }

    labels = {
      environment = var.environment
      service     = "engine-worker"
      severity    = "critical"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "sum(adept_engine_worker_thread_alive{job=\"adept-engine-worker\", environment=\"${var.environment}\"}) < max(adept_engine_worker_configured_threads{job=\"adept-engine-worker\", environment=\"${var.environment}\"})"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  rule {
    name           = "WorkerPollingStalled"
    for            = "5m"
    condition      = "B"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "Engine Worker idle polling is stalled"
      description = "No worker thread has executed a job poll loop within the last 5 minutes."
    }

    labels = {
      environment = var.environment
      service     = "engine-worker"
      severity    = "warning"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "(time() - max(adept_engine_worker_thread_last_poll_timestamp_seconds{job=\"adept-engine-worker\", environment=\"${var.environment}\"})) > 300"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  rule {
    name           = "WorkerQueueDelayOverdue"
    for            = "10m"
    condition      = "B"
    no_data_state  = "OK"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "Sustained queue delay in background worker"
      description = "The oldest ready-to-run job has been waiting for more than 10 minutes."
    }

    labels = {
      environment = var.environment
      service     = "engine-worker"
      severity    = "warning"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "adept_engine_queue_oldest_ready_age_seconds{job=\"adept-engine-worker\", environment=\"${var.environment}\"} > 600"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  rule {
    name           = "WorkerDeadLetterJobsPresent"
    for            = "5m"
    condition      = "B"
    no_data_state  = "OK"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "Dead-letter jobs present in worker queue"
      description = "One or more background jobs failed all retries and entered dead_letter status."
    }

    labels = {
      environment = var.environment
      service     = "engine-worker"
      severity    = "warning"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "adept_engine_queue_dead_letter_jobs{job=\"adept-engine-worker\", environment=\"${var.environment}\"} > 0"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  rule {
    name           = "ApiHigh5xxErrorRate"
    for            = "5m"
    condition      = "B"
    no_data_state  = "OK"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "High API 5xx error rate"
      description = "Adept API HTTP 5xx responses exceed 5% of total requests over 5 minutes."
    }

    labels = {
      environment = var.environment
      service     = "api"
      severity    = "critical"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "(sum(rate(http_server_requests_seconds_count{status=~\"5..\", environment=\"${var.environment}\"}[5m])) / (sum(rate(http_server_requests_seconds_count{environment=\"${var.environment}\"}[5m])) > 0)) > 0.05"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  rule {
    name           = "HostDiskSpaceLow"
    for            = "10m"
    condition      = "B"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "Host root filesystem disk space is low"
      description = "Host root filesystem disk usage exceeds 85%."
    }

    labels = {
      environment = var.environment
      service     = "host"
      severity    = "warning"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "(1 - (node_filesystem_avail_bytes{mountpoint=\"/host/root\", job=\"alloy-node\", environment=\"${var.environment}\"} / node_filesystem_size_bytes{mountpoint=\"/host/root\", job=\"alloy-node\", environment=\"${var.environment}\"})) > 0.85"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  rule {
    name           = "HostMemoryPressure"
    for            = "10m"
    condition      = "B"
    no_data_state  = "Alerting"
    exec_err_state = "Alerting"
    is_paused      = var.alerts_paused

    annotations = {
      summary     = "Host RAM usage is critically high"
      description = "Host available memory is below 10% (RAM usage > 90%) for 10 minutes."
    }

    labels = {
      environment = var.environment
      service     = "host"
      severity    = "warning"
    }

    data {
      ref_id         = "A"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        editorMode = "code"
        expr       = "(1 - (node_memory_MemAvailable_bytes{job=\"alloy-node\", environment=\"${var.environment}\"} / node_memory_MemTotal_bytes{job=\"alloy-node\", environment=\"${var.environment}\"})) > 0.90"
        instant    = true
        refId      = "A"
      })
    }

    data {
      ref_id         = "B"
      query_type     = ""
      relative_time_range {
        from = 300
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        conditions = [
          {
            evaluator = {
              params = [0]
              type   = "gt"
            }
            operator = {
              type = "and"
            }
            query = {
              params = ["A"]
            }
            reducer = {
              params = []
              type   = "last"
            }
            type = "query"
          }
        ]
        datasource = {
          name = "Expression"
          type = "__expr__"
          uid  = "__expr__"
        }
        expression = "A"
        refId      = "B"
        type       = "classic_conditions"
      })
    }
  }

  dynamic "rule" {
    for_each = var.notification_test_enabled ? [1] : []

    content {
      name           = "NotificationTest"
      for            = "0s"
      condition      = "B"
      no_data_state  = "OK"
      exec_err_state = "Alerting"
      is_paused      = false

      annotations = {
        summary     = "Controlled Grafana notification delivery test"
        description = "This rule verifies that alert routing and email templates function correctly without stopping any production services."
      }

      labels = {
        environment = var.environment
        service     = "test"
        severity    = "info"
      }

      data {
        ref_id         = "A"
        query_type     = ""
        relative_time_range {
          from = 300
          to   = 0
        }
        datasource_uid = var.prometheus_datasource_uid
        model = jsonencode({
          editorMode = "code"
          expr       = var.notification_test_firing ? "vector(1)" : "vector(0)"
          instant    = true
          refId      = "A"
        })
      }

      data {
        ref_id         = "B"
        query_type     = ""
        relative_time_range {
          from = 300
          to   = 0
        }
        datasource_uid = "__expr__"
        model = jsonencode({
          conditions = [
            {
              evaluator = {
                params = [0]
                type   = "gt"
              }
              operator = {
                type = "and"
              }
              query = {
                params = ["A"]
              }
              reducer = {
                params = []
                type   = "last"
              }
              type = "query"
            }
          ]
          datasource = {
            name = "Expression"
            type = "__expr__"
            uid  = "__expr__"
          }
          expression = "A"
          refId      = "B"
          type       = "classic_conditions"
        })
      }
    }
  }
}
