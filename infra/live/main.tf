# The root the pipeline plans and applies, once per environment. Its init names the environment's
# state bucket and key; its plan names the environment, its account and the KMS key that encrypts
# the state, each as a -var, and saves the plan to a file that the apply stage applies unchanged.
# Each environment lives in its own AWS account with its own state file, lock and key.

terraform {
  required_version = "1.13.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.73.0"
    }
  }
  backend "s3" {
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true # S3 conditional writes: a second apply waits instead of overwriting
  }
  # OpenTofu encrypts the state and the saved plan before they leave the runner.
  encryption {
    key_provider "aws_kms" "state" {
      kms_key_id = var.state_kms_key_id
      region     = "us-east-1"
      key_spec   = "AES_256"
    }
    method "aes_gcm" "state" {
      keys = key_provider.aws_kms.state
    }
    state {
      method   = method.aes_gcm.state
      enforced = true
    }
    plan {
      method   = method.aes_gcm.state
      enforced = true
    }
  }
}

variable "environment" {
  description = "Which environment this run plans: staging or production"
  type        = string
}

variable "account_id" {
  description = "The AWS account of that environment; the provider refuses any other"
  type        = string
}

variable "state_kms_key_id" {
  description = "KMS key that encrypts this environment's state and plans"
  type        = string
}

provider "aws" {
  region              = "us-east-1"
  allowed_account_ids = [var.account_id]
  default_tags {
    tags = { Project = "seatwave", Environment = var.environment, Owner = "seatwave-platform" }
  }
}

locals {
  # The only differences between the environments, side by side so a review sees them all.
  settings = {
    staging = {
      vpc_cidr               = "10.36.0.0/16"
      nodes_at_rest_per_zone = 1
      nodes_at_peak_per_zone = 2
      nodes_needed_at_peak   = 2
      database_clusters      = ["seatwave-staging-db-a"]
      backup_retention_days  = 7
      on_demand_vcpu_quota   = 40
    }
    production = {
      vpc_cidr               = "10.35.0.0/16"
      nodes_at_rest_per_zone = 3
      nodes_at_peak_per_zone = 10
      nodes_needed_at_peak   = 10
      database_clusters      = ["seatwave-db-a", "seatwave-db-b"]
      backup_retention_days  = 35
      on_demand_vcpu_quota   = 160
    }
  }[var.environment]
}

module "environment" {
  source                  = "../modules/seatwave-environment"
  environment             = var.environment
  vpc_cidr                = local.settings.vpc_cidr
  data_zone_ids           = ["use1-az1", "use1-az2", "use1-az4"]
  compute_zone_ids        = ["use1-az1", "use1-az2"]
  kubernetes_version      = "1.37"
  instance_type           = "m7i.xlarge"
  nodes_at_rest_per_zone  = local.settings.nodes_at_rest_per_zone
  nodes_at_peak_per_zone  = local.settings.nodes_at_peak_per_zone
  nodes_needed_at_peak    = local.settings.nodes_needed_at_peak
  database_clusters       = local.settings.database_clusters
  database_instance_class = "db.m6id.xlarge"
  postgres_engine_version = "18.6"
  database_storage_gb     = 200
  cache_node_type         = "cache.m7g.large"
  cache_engine_version    = "9.1"
  broker_instance_type    = "kafka.m7g.large"
  kafka_version           = "3.9.x"
  broker_storage_gb       = 100
  booking_events_topic    = "seatwave.bookings"
  backup_retention_days   = local.settings.backup_retention_days
  on_demand_vcpu_quota    = local.settings.on_demand_vcpu_quota
}

output "environment" {
  description = "The module's outputs for this environment"
  value       = module.environment
}
