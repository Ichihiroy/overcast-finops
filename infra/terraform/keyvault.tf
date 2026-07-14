# Risk-accepted: no network_acls default-deny. Pods read secrets from the Key
# Vault DATA plane over its public endpoint using workload identity (federated
# credential below) — there is no private endpoint, and the AKS egress IP is
# AKS-managed/unstable, so `default_action = "Deny"` would break CSI secret sync
# (backend readiness) and CI secret writes from the GitHub runner. Access is
# still gated by Azure RBAC (rbac_authorization_enabled) + workload identity.
# Revisit when a Key Vault private endpoint is added. See security/SECURITY.md.
#trivy:ignore:AVD-AZU-0013 exp:2027-01-01
resource "azurerm_key_vault" "main" {
  name                       = "kv-${local.name_compact}"
  resource_group_name        = data.azurerm_resource_group.main.name
  location                   = data.azurerm_resource_group.main.location
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true # Azure RBAC, not legacy access policies
  purge_protection_enabled   = true
  tags                       = var.tags
}

# The deploying identity (Platform-RW in CI) needs to write the SQL secrets below.
resource "azurerm_role_assignment" "deployer_kv_officer" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

# ── Workload identity for the backend ServiceAccount ─────────────────
resource "azurerm_user_assigned_identity" "app" {
  name                = "id-${local.name_prefix}-app"
  resource_group_name = data.azurerm_resource_group.main.name
  location            = data.azurerm_resource_group.main.location
  tags                = var.tags
}

resource "azurerm_role_assignment" "app_kv_secrets_user" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.app.principal_id
}

# Federate the identity to the backend ServiceAccount in each namespace:
# the CSI driver / pod exchanges its projected SA token for an Entra token.
resource "azurerm_federated_identity_credential" "app" {
  for_each = toset([var.app_namespace, var.app_staging_namespace])

  name      = "fic-${each.value}-${var.app_service_account}"
  parent_id = azurerm_user_assigned_identity.app.id
  issuer    = azurerm_kubernetes_cluster.main.oidc_issuer_url
  subject   = "system:serviceaccount:${each.value}:${var.app_service_account}"
  audience  = ["api://AzureADTokenExchange"]
}
