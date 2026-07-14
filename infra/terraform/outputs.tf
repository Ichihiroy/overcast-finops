output "aks_name" {
  description = "AKS cluster name (az aks get-credentials)."
  value       = azurerm_kubernetes_cluster.main.name
}

output "resource_group_name" {
  description = "Main resource group."
  value       = data.azurerm_resource_group.main.name
}

output "node_resource_group" {
  description = "Auto-managed resource group holding AKS node resources."
  value       = azurerm_kubernetes_cluster.main.node_resource_group
}

output "acr_login_server" {
  description = "ACR login server; image repo prefix used by CI."
  value       = azurerm_container_registry.main.login_server
}

output "sql_fqdn" {
  description = "Azure SQL server FQDN (resolves privately inside the VNet)."
  value       = azurerm_mssql_server.main.fully_qualified_domain_name
}

output "key_vault_uri" {
  description = "Key Vault URI."
  value       = azurerm_key_vault.main.vault_uri
}

output "key_vault_name" {
  description = "Key Vault name (referenced by the SecretProviderClass)."
  value       = azurerm_key_vault.main.name
}

output "app_identity_client_id" {
  description = "Client id of the user-assigned identity for the backend ServiceAccount annotation."
  value       = azurerm_user_assigned_identity.app.client_id
}

output "oidc_issuer_url" {
  description = "AKS OIDC issuer URL (federated credentials)."
  value       = azurerm_kubernetes_cluster.main.oidc_issuer_url
}

output "ingress_public_ip" {
  description = "Static public IP bound to the ingress-nginx load balancer."
  value       = azurerm_public_ip.ingress.ip_address
}

output "ingress_fqdn" {
  description = "Stable ingress hostname (Azure DNS label) — use as FRONTEND_HOST_* and for TLS."
  value       = azurerm_public_ip.ingress.fqdn
}
