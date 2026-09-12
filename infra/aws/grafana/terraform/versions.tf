terraform {
  required_version = ">= 1.6.0"

  required_providers {
    grafana = {
      source  = "grafana/grafana"
      version = "~> 4.46.0"
    }
  }
}

provider "grafana" {
  url  = trimsuffix(var.grafana_url, "/")
  auth = var.grafana_auth
}
