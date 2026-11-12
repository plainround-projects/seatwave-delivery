output "cluster_name" {
  description = "EKS cluster the pipeline deploys to"
  value       = aws_eks_cluster.seatwave.name
}

output "node_groups" {
  description = "Node group name per zone ID"
  value       = { for zone, group in aws_eks_node_group.zone : zone => group.node_group_name }
}

output "database_writer_endpoints" {
  description = "Writer endpoint per database cluster, for Module 18's shard directory"
  value       = { for name, cluster in aws_rds_cluster.shard : name => cluster.endpoint }
}

output "database_secret_arns" {
  description = "Secrets Manager ARN of each cluster's owner password (the ARN, never the password)"
  value       = { for name, cluster in aws_rds_cluster.shard : name => cluster.master_user_secret[0].secret_arn }
}

output "private_subnet_ids" {
  description = "Private subnet per zone ID"
  value       = { for zone, subnet in aws_subnet.private : zone => subnet.id }
}

output "cache_primary_endpoint" {
  description = "The cache's primary endpoint (TLS)"
  value       = aws_elasticache_replication_group.cache.primary_endpoint_address
}

output "kafka_bootstrap_brokers" {
  description = "MSK bootstrap brokers for TLS with IAM authentication, one per zone"
  value       = aws_msk_cluster.kafka.bootstrap_brokers_sasl_iam
}

output "kafka_producer_policy_arn" {
  description = "IAM policy that lets the seatwave pods write booking events, for Module 38's pod role"
  value       = aws_iam_policy.seatwave_producer.arn
}
