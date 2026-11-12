# The inputs that make one environment differ from another. Everything else is the same code.

variable "environment" {
  description = "staging or production; production keeps Module 34's names, others get a suffix"
  type        = string
  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production."
  }
}

variable "vpc_cidr" {
  description = "Address range of the environment's network"
  type        = string
}

variable "data_zone_ids" {
  description = "Zone IDs for subnets that hold state: three, so a quorum survives one loss (Module 34)"
  type        = list(string)
  validation {
    condition     = length(var.data_zone_ids) == 3
    error_message = "state needs three zones (Module 34)."
  }
}

variable "compute_zone_ids" {
  description = "Zone IDs that run nodes; each must hold the peak's whole need (static stability)"
  type        = list(string)
}

variable "kubernetes_version" {
  description = "EKS control plane version (1.37 is in standard support, checked 11 November 2026)"
  type        = string
}

variable "instance_type" {
  description = "Node instance type (Module 1's reference machine is m7i.xlarge)"
  type        = string
}

variable "nodes_at_rest_per_zone" {
  description = "Nodes each zone runs at rest (Module 34: 3)"
  type        = number
}

variable "nodes_at_peak_per_zone" {
  description = "The most nodes each zone may run (Module 34: 10, so either zone alone carries the peak)"
  type        = number
}

variable "nodes_needed_at_peak" {
  description = "Nodes the on-sale peak needs in all (Module 33: 10)"
  type        = number
}

variable "database_clusters" {
  description = "RDS Multi-AZ DB cluster identifiers, each a writer and two readers in three zones"
  type        = list(string)
}

variable "database_instance_class" {
  description = "Instance class of every database cluster (Module 34: db.m6id.xlarge)"
  type        = string
}

variable "postgres_engine_version" {
  description = "RDS for PostgreSQL version (18.6, released on RDS 25 August 2026)"
  type        = string
}

variable "database_storage_gb" {
  description = "Provisioned gp3 storage per cluster (Module 34: 200)"
  type        = number
}

variable "backup_retention_days" {
  description = "Days of automated backups kept; closes Module 34's open finding on retention"
  type        = number
  validation {
    condition     = var.backup_retention_days >= 7
    error_message = "keep at least 7 days, so a bad change found after a weekend can be undone."
  }
}

variable "cache_node_type" {
  description = "ElastiCache node type (Module 34: cache.m7g.large)"
  type        = string
}

variable "cache_engine_version" {
  description = "ElastiCache for Valkey version (9.1 is the newest listed, checked 12 November 2026)"
  type        = string
}

variable "broker_instance_type" {
  description = "MSK broker type (Module 34: kafka.m7g.large)"
  type        = string
}

variable "kafka_version" {
  description = "MSK Apache Kafka version (3.9.x is the one MSK recommends, checked 12 November 2026)"
  type        = string
}

variable "broker_storage_gb" {
  description = "EBS storage per broker (Module 34: 100, so $30.00 a month for three)"
  type        = number
}

variable "booking_events_topic" {
  description = "The topic the seatwave pods produce booking events to (Module 27)"
  type        = string
}

variable "on_demand_vcpu_quota" {
  description = "Standard on-demand vCPU quota to request: twice the peak (Module 34: 160)"
  type        = number
}
