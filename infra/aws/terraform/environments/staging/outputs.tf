output "runtime_enabled" {
  description = "Whether disposable Lightsail runtime resources are enabled."
  value       = var.runtime_enabled
}

output "application_url" {
  description = "Canonical public URL used by users and OAuth callbacks."
  value       = local.application_url
}

output "route53_hosted_zone_id" {
  description = "ID of the retained public Route 53 hosted zone."
  value       = aws_route53_zone.application.zone_id
}

output "route53_name_servers" {
  description = "Authoritative name servers to configure at the external registrar only after the hosted zone is applied and reviewed."
  value       = sort(aws_route53_zone.application.name_servers)
}

output "application_dns_name" {
  description = "Created application DNS record, or null while the runtime is disabled."
  value       = try(aws_route53_record.application_ipv4["primary"].fqdn, null)
}

output "instance_name" {
  description = "Lightsail instance name, or null while the runtime is disabled."
  value       = try(aws_lightsail_instance.app["primary"].name, null)
}

output "static_ip_address" {
  description = "Attached static IPv4 address, or null while the runtime is disabled."
  value       = try(aws_lightsail_static_ip.app["primary"].ip_address, null)
}
