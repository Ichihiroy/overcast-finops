variable "subscription_id" {
  description = "Azure subscription id. Leave null to use ARM_SUBSCRIPTION_ID (CI/OIDC)."
  type        = string
  default     = null
}

variable "team" {
  description = "Team slug prefixed to every resource name so parallel teams in the same subscription/region never collide (matters most for globally-unique names: ACR, Key Vault, SQL server)."
  type        = string
  default     = "thelocals"
}

variable "project" {
  description = "Short project slug used in resource names."
  type        = string
  default     = "ironhack"
}

variable "environment" {
  description = "Environment slug used in resource names."
  type        = string
  default     = "prod"
}

variable "location" {
  description = "Azure region for all resources."
  type        = string
  default     = "westeurope"
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default = {
    team       = "the-locals"
    project    = "ironhack-final"
    managed_by = "terraform"
  }
}

# ── Network ───────────────────────────────────────────────────────────
variable "vnet_address_space" {
  description = "VNet CIDR."
  type        = string
  default     = "10.0.0.0/16"
}

variable "snet_aks_cidr" {
  description = "AKS node/pod subnet CIDR (Azure CNI allocates pod IPs here)."
  type        = string
  default     = "10.0.0.0/20"
}

variable "snet_data_cidr" {
  description = "Data subnet CIDR (private endpoints only)."
  type        = string
  default     = "10.0.16.0/24"
}

# ── AKS ───────────────────────────────────────────────────────────────
variable "kubernetes_version" {
  description = "AKS Kubernetes version prefix (null = latest default)."
  type        = string
  default     = null
}

variable "system_node_vm_size" {
  description = "VM size for the system node pool. DSv3 family (not DSv5): the target subscription has 0 DSv5 vCPU quota in westeurope; DSv3 is equivalent (2 vCPU, premium-storage capable)."
  type        = string
  default     = "Standard_D2s_v3"
}

variable "system_node_min_count" {
  type    = number
  default = 2
}

variable "system_node_max_count" {
  type    = number
  default = 3
}

variable "user_node_vm_size" {
  description = "VM size for the user (workload) node pool. DSv3 family (not DSv5): the target subscription has 0 DSv5 vCPU quota in westeurope; DSv3 is equivalent (4 vCPU, premium-storage capable)."
  type        = string
  default     = "Standard_D4s_v3"
}

variable "user_node_min_count" {
  type    = number
  default = 2
}

variable "user_node_max_count" {
  type    = number
  default = 6
}

# ── Workload identity (app ServiceAccount ↔ Key Vault) ───────────────
variable "app_namespace" {
  description = "Kubernetes namespace of the app (federated credential subject)."
  type        = string
  default     = "app-production"
}

variable "app_service_account" {
  description = "Kubernetes ServiceAccount name of the backend."
  type        = string
  default     = "backend"
}

variable "app_staging_namespace" {
  description = "Kubernetes staging namespace (second federated credential subject)."
  type        = string
  default     = "app-staging"
}

# ── GitOps (Argo CD) ──────────────────────────────────────────────────
variable "gitops_repo_url" {
  description = "Git repo Argo CD watches (charts under deploy/, desired state under gitops/). Public repo — no credentials needed."
  type        = string
  default     = "https://github.com/Ichihiroy/ironhack-final.git"
}

variable "gitops_revision" {
  description = "Git revision Argo CD tracks."
  type        = string
  default     = "main"
}

variable "frontend_host_staging" {
  description = "Ingress host for the staging frontend (injected into the Argo CD Application; CI sets it via TF_VAR from the FRONTEND_HOST_STAGING repo variable)."
  type        = string
  default     = ""
}

variable "frontend_host_production" {
  description = "Ingress host for the production frontend (injected into the Argo CD Application; CI sets it via TF_VAR from the FRONTEND_HOST_PRODUCTION repo variable)."
  type        = string
  default     = ""
}

# ── Azure SQL ─────────────────────────────────────────────────────────
variable "sql_admin_login" {
  description = "Azure SQL administrator login name (password is generated and stored in Key Vault)."
  type        = string
  default     = "sqladminuser"
}

variable "sql_database_sku" {
  description = "Azure SQL Database SKU."
  type        = string
  default     = "S0"
}
