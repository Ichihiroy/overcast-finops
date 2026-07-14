# ============================================================================
# In-cluster platform add-ons (ingress, TLS, observability)
#
# Installed by the SAME apply that creates the cluster, for the same reason
# Argo CD and Kyverno are (gitops.tf): the training subscription is wiped
# nightly, so anything installed by hand dies with it. One infra-apply run
# must bring the whole platform back — GUIDE.md "morning kickstart".
#
# What runs (Prometheus rules, ServiceMonitor, Grafana dashboards, network
# policies) still lives in git and is synced by Argo CD (see gitops.tf);
# Terraform only installs the operators/controllers those manifests need.
# ============================================================================

# Static ingress IP. It lives in the APP resource group — not the AKS node
# RG — because the CI apply identity (platform-rw) is Contributor on the app
# RG only (ADR-0006 least privilege) and gets 403 creating resources in
# MC_*. The trade-off: the cluster's cloud controller must be allowed to
# bind an IP outside its node RG, granted just below. The domain_name_label
# gives a free, stable FQDN (<label>.<region>.cloudapp.azure.com) — enough
# for DNS + TLS without buying a domain, and re-claimed identically on every
# morning rebuild.
resource "azurerm_public_ip" "ingress" {
  name                = "pip-${local.name_prefix}-ingress"
  resource_group_name = data.azurerm_resource_group.main.name
  location            = var.location
  allocation_method   = "Static"
  sku                 = "Standard"
  domain_name_label   = local.name_prefix
  tags                = var.tags
}

# The documented AKS pattern for a static IP outside the node RG: the cluster
# identity needs Network Contributor on the IP's resource group so the Azure
# cloud controller can attach it to the load balancer. platform-rw can grant
# this because bootstrap gave it RBAC Administrator on the app RG.
resource "azurerm_role_assignment" "aks_ingress_ip" {
  scope                = data.azurerm_resource_group.main.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_kubernetes_cluster.main.identity[0].principal_id
}

resource "helm_release" "ingress_nginx" {
  name             = "ingress-nginx"
  namespace        = "ingress-nginx"
  create_namespace = true
  repository       = "https://kubernetes.github.io/ingress-nginx"
  chart            = "ingress-nginx"
  version          = "4.15.1"
  timeout          = 600

  values = [yamlencode({
    controller = {
      service = {
        loadBalancerIP = azurerm_public_ip.ingress.ip_address
        annotations = {
          "service.beta.kubernetes.io/azure-load-balancer-resource-group" = data.azurerm_resource_group.main.name
        }
      }
    }
  })]

  depends_on = [
    azurerm_kubernetes_cluster_node_pool.user,
    azurerm_role_assignment.aks_ingress_ip, # LB can't bind the IP before this
  ]
}

resource "helm_release" "cert_manager" {
  name             = "cert-manager"
  namespace        = "cert-manager"
  create_namespace = true
  repository       = "https://charts.jetstack.io"
  chart            = "cert-manager"
  version          = "v1.21.0"
  timeout          = 600

  values = [yamlencode({
    crds = { enabled = true }
  })]

  depends_on = [azurerm_kubernetes_cluster_node_pool.user]
}

# Grafana admin password: generated here, stored ONLY in Key Vault (never in
# git, never in a values file). Retrieve with:
#   az keyvault secret show --vault-name <kv> --name grafana-admin-password
resource "random_password" "grafana_admin" {
  length  = 24
  special = false # avoids quoting surprises when pasted into a login form
}

resource "azurerm_key_vault_secret" "grafana_admin" {
  name         = "grafana-admin-password"
  value        = random_password.grafana_admin.result
  key_vault_id = azurerm_key_vault.main.id

  depends_on = [azurerm_role_assignment.deployer_kv_officer]
}

# ── Azure OpenAI wiring (optional) ────────────────────────────────────
# Key path: GitHub Actions SECRET → TF_VAR → Key Vault → CSI-synced K8s
# Secret → backend env vars. With no key the secrets are simply absent and
# gitops.tf flips secretProviderClass.aiEnabled off, so the CSI mount never
# references missing Key Vault objects and the app runs in fallback mode.
locals {
  # nonsensitive: a bare on/off flag must not taint the Argo app specs.
  ai_enabled = nonsensitive(var.azure_openai_api_key != "")
}

resource "azurerm_key_vault_secret" "azure_openai_endpoint" {
  count        = local.ai_enabled ? 1 : 0
  name         = "azure-openai-endpoint"
  value        = var.azure_openai_endpoint
  key_vault_id = azurerm_key_vault.main.id

  depends_on = [azurerm_role_assignment.deployer_kv_officer]
}

resource "azurerm_key_vault_secret" "azure_openai_api_key" {
  count        = local.ai_enabled ? 1 : 0
  name         = "azure-openai-api-key"
  value        = var.azure_openai_api_key
  key_vault_id = azurerm_key_vault.main.id

  depends_on = [azurerm_role_assignment.deployer_kv_officer]
}

resource "azurerm_key_vault_secret" "azure_openai_deployment" {
  count        = local.ai_enabled ? 1 : 0
  name         = "azure-openai-deployment"
  value        = var.azure_openai_deployment
  key_vault_id = azurerm_key_vault.main.id

  depends_on = [azurerm_role_assignment.deployer_kv_officer]
}

resource "azurerm_key_vault_secret" "azure_openai_api_version" {
  count        = local.ai_enabled ? 1 : 0
  name         = "azure-openai-api-version"
  value        = var.azure_openai_api_version
  key_vault_id = azurerm_key_vault.main.id

  depends_on = [azurerm_role_assignment.deployer_kv_officer]
}

resource "helm_release" "kube_prometheus_stack" {
  name             = "kps"
  namespace        = "monitoring"
  create_namespace = true
  repository       = "https://prometheus-community.github.io/helm-charts"
  chart            = "kube-prometheus-stack"
  version          = "87.15.1"
  timeout          = 900 # CRDs + operator + prometheus: slowest of the add-ons

  values = [
    file("${path.module}/../../observability/kube-prometheus-stack-values.yaml"),
    yamlencode({
      grafana = { adminPassword = random_password.grafana_admin.result }
    }),
  ]

  depends_on = [azurerm_kubernetes_cluster_node_pool.user]
}
