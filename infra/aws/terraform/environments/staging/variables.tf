variable "aws_region" {
  description = "AWS Region for the staging deployment."
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "Deployment environment name used in tags and resource names."
  type        = string
  default     = "staging"
}

variable "project_name" {
  description = "Project name used in tags and resource names."
  type        = string
  default     = "adept"
}

variable "domain_name" {
  description = "Canonical public hostname for Adept. Provide a hostname only, without a scheme or path."
  type        = string
  default     = "adeptindustries.dev"

  validation {
    condition     = can(regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$", var.domain_name))
    error_message = "domain_name must be a lowercase DNS hostname without https:// or a path."
  }
}

variable "runtime_enabled" {
  description = "Master safety switch for all disposable Lightsail runtime resources. Keep false until a reviewed plan is ready; set false for emergency shutdown."
  type        = bool
  default     = false
}

variable "availability_zone" {
  description = "Availability Zone for the Lightsail instance. Confirm it from the live catalog after enabling Lightsail."
  type        = string
  default     = "ap-south-1a"

  validation {
    condition     = can(regex("^ap-south-1[a-z]$", var.availability_zone))
    error_message = "availability_zone must be an ap-south-1 Availability Zone."
  }
}

variable "lightsail_blueprint_id" {
  description = "Reviewed Ubuntu 24.04 x86_64 Lightsail blueprint ID."
  type        = string
  default     = "ubuntu_24_04"
}

variable "lightsail_bundle_id" {
  description = "Reviewed 4 GB Lightsail bundle ID from the live catalog. Required only when runtime_enabled is true."
  type        = string
  default     = ""
}

variable "ssh_public_key_path" {
  description = "Local path to the team-owned SSH public key. Terraform never reads or stores the private key."
  type        = string
  default     = ""
}

variable "server_user" {
  description = "Default operating-system user created by the reviewed Lightsail Ubuntu blueprint."
  type        = string
  default     = "ubuntu"

  validation {
    condition     = can(regex("^[a-z_][a-z0-9_-]{0,31}$", var.server_user))
    error_message = "server_user must be a valid lowercase Linux username."
  }
}

variable "admin_ssh_cidrs" {
  description = "Trusted IPv4 CIDRs allowed to use SSH. Never use 0.0.0.0/0."
  type        = set(string)
  default     = []

  validation {
    condition = alltrue([
      for cidr in var.admin_ssh_cidrs : can(cidrnetmask(cidr)) && cidr != "0.0.0.0/0"
    ])
    error_message = "Each admin_ssh_cidrs value must be a valid restricted IPv4 CIDR; 0.0.0.0/0 is forbidden."
  }
}

variable "automatic_snapshot_time" {
  description = "UTC time for the daily Lightsail automatic snapshot."
  type        = string
  default     = "03:00"

  validation {
    condition     = can(regex("^(?:[01][0-9]|2[0-3]):[0-5][0-9]$", var.automatic_snapshot_time))
    error_message = "automatic_snapshot_time must use 24-hour HH:MM UTC format."
  }
}
