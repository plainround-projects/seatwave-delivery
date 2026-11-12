# SeatWave's environment, Module 34's reference design as code: a network with subnets per zone,
# a NAT gateway per compute zone, the EKS cluster with one node group per zone, the database
# clusters in three zones, the cache in two and the brokers in three, and the vCPU quota the
# on-sale needs. Tags come from the root's
# provider default_tags, so every resource carries Project, Environment and Owner.

terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.73.0"
    }
  }
}

locals {
  # Production keeps Module 34's names; staging's say what they are.
  name = var.environment == "production" ? "seatwave" : "seatwave-${var.environment}"
}

resource "aws_vpc" "seatwave" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  tags                 = { Name = local.name }
}

resource "aws_subnet" "private" {
  for_each             = toset(var.data_zone_ids)
  vpc_id               = aws_vpc.seatwave.id
  availability_zone_id = each.key
  cidr_block           = cidrsubnet(var.vpc_cidr, 4, index(var.data_zone_ids, each.key))
  tags                 = { Name = "${local.name}-private-${each.key}" }
}

resource "aws_subnet" "public" {
  for_each             = toset(var.compute_zone_ids)
  vpc_id               = aws_vpc.seatwave.id
  availability_zone_id = each.key
  # The upper half of the range, so adding a data zone never moves a public subnet.
  cidr_block = cidrsubnet(var.vpc_cidr, 4, 8 + index(var.compute_zone_ids, each.key))
  tags       = { Name = "${local.name}-public-${each.key}" }
}

resource "aws_internet_gateway" "seatwave" {
  vpc_id = aws_vpc.seatwave.id
}

resource "aws_eip" "nat" {
  for_each = toset(var.compute_zone_ids)
  domain   = "vpc"
}

# One NAT gateway per compute zone: a lost zone takes only its own (Module 34).
resource "aws_nat_gateway" "zone" {
  for_each      = toset(var.compute_zone_ids)
  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id
  depends_on    = [aws_internet_gateway.seatwave]
}

resource "aws_route_table" "private" {
  for_each = toset(var.compute_zone_ids)
  vpc_id   = aws_vpc.seatwave.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.zone[each.key].id
  }
}

resource "aws_route_table_association" "private" {
  for_each       = toset(var.compute_zone_ids)
  subnet_id      = aws_subnet.private[each.key].id
  route_table_id = aws_route_table.private[each.key].id
}

data "aws_iam_policy_document" "assume" {
  for_each = { cluster = "eks.amazonaws.com", node = "ec2.amazonaws.com" }
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = [each.value]
    }
  }
}

resource "aws_iam_role" "eks" {
  for_each           = data.aws_iam_policy_document.assume
  name               = "${local.name}-eks-${each.key}"
  assume_role_policy = each.value.json
}

# Only the AWS-managed policies EKS documents for each role; nothing broader (least privilege).
resource "aws_iam_role_policy_attachment" "eks" {
  for_each = {
    "cluster/AmazonEKSClusterPolicy"          = "cluster"
    "node/AmazonEKSWorkerNodePolicy"          = "node"
    "node/AmazonEKS_CNI_Policy"               = "node"
    "node/AmazonEC2ContainerRegistryPullOnly" = "node"
  }
  role       = aws_iam_role.eks[each.value].name
  policy_arn = "arn:aws:iam::aws:policy/${split("/", each.key)[1]}"
}

resource "aws_eks_cluster" "seatwave" {
  name     = local.name
  role_arn = aws_iam_role.eks["cluster"].arn
  version  = var.kubernetes_version
  vpc_config {
    subnet_ids              = [for zone in var.data_zone_ids : aws_subnet.private[zone].id]
    endpoint_private_access = true
    endpoint_public_access  = false
  }
  access_config {
    authentication_mode = "API"
  }
  depends_on = [aws_iam_role_policy_attachment.eks]
}

resource "aws_eks_node_group" "zone" {
  for_each        = toset(var.compute_zone_ids)
  cluster_name    = aws_eks_cluster.seatwave.name
  node_group_name = "${local.name}-${each.key}"
  node_role_arn   = aws_iam_role.eks["node"].arn
  subnet_ids      = [aws_subnet.private[each.key].id]
  instance_types  = [var.instance_type]
  scaling_config {
    min_size     = var.nodes_at_rest_per_zone
    desired_size = var.nodes_at_rest_per_zone
    max_size     = var.nodes_at_peak_per_zone
  }
  lifecycle {
    # The cluster autoscaler owns the running count; a change it makes is not drift.
    ignore_changes = [scaling_config[0].desired_size]
    precondition {
      condition     = length(var.compute_zone_ids) >= 2 && var.nodes_at_peak_per_zone >= var.nodes_needed_at_peak
      error_message = "Static stability: at least two compute zones, each able to run the peak's whole need."
    }
  }
}

resource "aws_kms_key" "data" {
  description             = "${local.name} database storage"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_db_subnet_group" "seatwave" {
  name       = local.name
  subnet_ids = [for zone in var.data_zone_ids : aws_subnet.private[zone].id]
}

# The data stores accept connections only from the cluster's pods (the EKS cluster security group),
# each on its own port; nothing else in the network, and nothing from outside it, can reach them.
resource "aws_security_group" "data_stores" {
  name        = "${local.name}-data-stores"
  description = "SeatWave data stores: reachable from the EKS cluster only"
  vpc_id      = aws_vpc.seatwave.id
}

resource "aws_vpc_security_group_ingress_rule" "data_stores" {
  for_each = {
    postgres  = 5432 # RDS for PostgreSQL
    valkey    = 6379 # ElastiCache for Valkey, TLS
    kafka_iam = 9098 # MSK, TLS with IAM authentication
  }
  security_group_id            = aws_security_group.data_stores.id
  referenced_security_group_id = aws_eks_cluster.seatwave.vpc_config[0].cluster_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = each.value
  to_port                      = each.value
  description                  = "${each.key} from the EKS cluster"
}

# A Multi-AZ DB cluster: a writer and two readers in the subnet group's three zones (Module 34).
resource "aws_rds_cluster" "shard" {
  for_each                    = toset(var.database_clusters)
  cluster_identifier          = each.key
  engine                      = "postgres"
  engine_version              = var.postgres_engine_version
  db_cluster_instance_class   = var.database_instance_class
  storage_type                = "gp3"
  allocated_storage           = var.database_storage_gb
  db_subnet_group_name        = aws_db_subnet_group.seatwave.name
  vpc_security_group_ids      = [aws_security_group.data_stores.id]
  master_username             = "seatwave_owner"
  manage_master_user_password = true # RDS keeps it in Secrets Manager; it never enters the state
  storage_encrypted           = true
  kms_key_id                  = aws_kms_key.data.arn
  backup_retention_period     = var.backup_retention_days
  deletion_protection         = true
  final_snapshot_identifier   = "${each.key}-final"
  lifecycle {
    prevent_destroy = true # a plan that would delete a shard's cluster fails instead
  }
}

# The cache: ElastiCache for Valkey, a primary and a replica in the two compute zones, so a lost
# zone fails over to the other (Module 34's seatwave-cache). Encrypted at rest with the
# environment's key and in transit with TLS required.
resource "aws_elasticache_subnet_group" "cache" {
  name       = "${local.name}-cache"
  subnet_ids = [for zone in var.compute_zone_ids : aws_subnet.private[zone].id]
}

resource "aws_elasticache_replication_group" "cache" {
  replication_group_id       = "${local.name}-cache"
  description                = "SeatWave seat maps and holds cache (Module 16)"
  engine                     = "valkey"
  engine_version             = var.cache_engine_version
  node_type                  = var.cache_node_type
  num_cache_clusters         = length(var.compute_zone_ids) # one node per compute zone
  automatic_failover_enabled = true
  multi_az_enabled           = true
  subnet_group_name          = aws_elasticache_subnet_group.cache.name
  security_group_ids         = [aws_security_group.data_stores.id]
  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.data.arn
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  snapshot_retention_limit   = 1 # a cache refills from the database; one day is enough
}

# The brokers: Amazon MSK, one broker in each of the three data zones, so 2 of a partition's 3
# replicas survive a zone loss (Module 27's min.insync.replicas 2; Module 34's seatwave-kafka).
# Clients authenticate with IAM over TLS, so each pod's role says which topics it may use.
resource "aws_msk_cluster" "kafka" {
  cluster_name           = "${local.name}-kafka"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = length(var.data_zone_ids)
  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = [for zone in var.data_zone_ids : aws_subnet.private[zone].id]
    security_groups = [aws_security_group.data_stores.id]
    storage_info {
      ebs_storage_info {
        volume_size = var.broker_storage_gb
      }
    }
  }
  client_authentication {
    unauthenticated = false
    sasl {
      iam = true
    }
  }
  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.data.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }
}

# What the seatwave pods may do on the brokers: connect to this one cluster, and describe and
# write the one topic they produce, idempotently. No wildcard action, no wildcard resource; the
# consumers' policies (notification-service, Module 39) name their own topic and group the same
# way. Module 38 attaches it to the pods' role.
data "aws_iam_policy_document" "seatwave_producer" {
  statement {
    sid       = "ConnectToTheCluster"
    actions   = ["kafka-cluster:Connect", "kafka-cluster:WriteDataIdempotently"]
    resources = [aws_msk_cluster.kafka.arn]
  }
  statement {
    sid     = "ProduceBookingEvents"
    actions = ["kafka-cluster:DescribeTopic", "kafka-cluster:WriteData"]
    # A topic's ARN is the cluster's with "topic" for "cluster", then the topic's name.
    resources = ["${replace(aws_msk_cluster.kafka.arn, ":cluster/", ":topic/")}/${var.booking_events_topic}"]
  }
}

resource "aws_iam_policy" "seatwave_producer" {
  name        = "${local.name}-kafka-producer"
  description = "Connect to ${local.name}-kafka and write ${var.booking_events_topic} only"
  policy      = data.aws_iam_policy_document.seatwave_producer.json
}

resource "aws_servicequotas_service_quota" "on_demand_vcpus" {
  service_code = "ec2"
  quota_code   = "L-1216C47A" # Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances
  value        = var.on_demand_vcpu_quota
}
