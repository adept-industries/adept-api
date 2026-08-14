locals {
  application_url = "https://${var.domain_name}"

  # Every disposable or chargeable runtime resource must use this exact gate.
  # Retained recovery resources such as the state bucket and future backup bucket
  # intentionally live outside it.
  runtime = var.runtime_enabled ? {
    primary = {
      instance_name = "${var.project_name}-${var.environment}-app"
    }
  } : {}
}

# DNS is retained independently from the disposable application runtime. After
# this zone is created and verified, delegate the domain to its name servers at
# the external registrar. prevent_destroy protects that delegation from an
# accidental terraform destroy or runtime shutdown.
resource "aws_route53_zone" "application" {
  name    = var.domain_name
  comment = "Public DNS for ${var.project_name} ${var.environment}"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_lightsail_key_pair" "deployer" {
  for_each = local.runtime

  name       = "${var.project_name}-${var.environment}-deployer"
  public_key = file(pathexpand(var.ssh_public_key_path))

  lifecycle {
    precondition {
      condition     = var.ssh_public_key_path != "" && fileexists(pathexpand(var.ssh_public_key_path))
      error_message = "Set ssh_public_key_path to an existing public key before enabling the runtime."
    }
  }
}

resource "aws_lightsail_instance" "app" {
  for_each = local.runtime

  name              = each.value.instance_name
  availability_zone = var.availability_zone
  blueprint_id      = var.lightsail_blueprint_id
  bundle_id         = var.lightsail_bundle_id
  ip_address_type   = "ipv4"
  key_pair_name     = aws_lightsail_key_pair.deployer[each.key].name
  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    deploy_user = var.server_user
  })

  add_on {
    type          = "AutoSnapshot"
    snapshot_time = var.automatic_snapshot_time
    status        = "Enabled"
  }

  lifecycle {
    precondition {
      condition     = trimspace(var.lightsail_bundle_id) != ""
      error_message = "Set lightsail_bundle_id from the live Mumbai catalog before enabling the runtime."
    }

    precondition {
      condition     = length(var.admin_ssh_cidrs) > 0
      error_message = "Set at least one restricted admin_ssh_cidrs value before enabling the runtime."
    }
  }
}

resource "aws_lightsail_instance_public_ports" "app" {
  for_each = local.runtime

  instance_name = aws_lightsail_instance.app[each.key].name

  port_info {
    protocol  = "tcp"
    from_port = 22
    to_port   = 22
    cidrs     = var.admin_ssh_cidrs
  }

  port_info {
    protocol  = "tcp"
    from_port = 80
    to_port   = 80
    cidrs     = ["0.0.0.0/0"]
  }

  port_info {
    protocol  = "tcp"
    from_port = 443
    to_port   = 443
    cidrs     = ["0.0.0.0/0"]
  }
}

resource "aws_lightsail_static_ip" "app" {
  for_each = local.runtime

  name = "${var.project_name}-${var.environment}-ip"
}

resource "aws_lightsail_static_ip_attachment" "app" {
  for_each = local.runtime

  instance_name  = aws_lightsail_instance.app[each.key].name
  static_ip_name = aws_lightsail_static_ip.app[each.key].name
}

# The user-facing apex record is disposable with the runtime. It cannot exist
# while runtime_enabled is false and therefore cannot retain a stale server IP.
resource "aws_route53_record" "application_ipv4" {
  for_each = local.runtime

  zone_id = aws_route53_zone.application.zone_id
  name    = var.domain_name
  type    = "A"
  ttl     = 300
  records = [aws_lightsail_static_ip.app[each.key].ip_address]
}
