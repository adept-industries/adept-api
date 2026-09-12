resource "grafana_folder" "adept" {
  title = "Adept"
}

resource "grafana_dashboard" "infrastructure" {
  folder      = grafana_folder.adept.uid
  config_json = file("${path.module}/../dashboards/infrastructure.json")
}

resource "grafana_dashboard" "application" {
  folder      = grafana_folder.adept.uid
  config_json = file("${path.module}/../dashboards/application.json")
}

resource "grafana_dashboard" "worker" {
  folder      = grafana_folder.adept.uid
  config_json = file("${path.module}/../dashboards/worker.json")
}
