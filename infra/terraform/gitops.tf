# ============================================================================
# GitOps + policy enforcement (ADR-0008, ADR-0009)
#
# Argo CD pulls desired state from git; CI never talks to the cluster.
# Split of responsibilities, deliberate:
#   - git (gitops/values/*.yaml)  → WHAT runs: image tags, per-env overrides
#   - Terraform (this file)       → WHERE/WHO: ACR login server, workload
#     identity client id, Key Vault name, tenant id — injected as Helm
#     parameters so no real GUID is ever committed (hard repo constraint).
#
# Kyverno enforces admission policies (gitops/policies/, synced by Argo CD):
# signed images, no :latest, ACR-only registries, non-root, resource limits.
# ============================================================================

locals {
  app_envs = {
    staging    = "app-staging"
    production = "app-production"
  }

  frontend_hosts = {
    staging    = var.frontend_host_staging
    production = var.frontend_host_production
  }

  argo_sync_policy = {
    automated = {
      prune    = true # deleted-in-git = deleted-in-cluster
      selfHeal = true # manual cluster edits are reverted — git is the truth
    }
    syncOptions = ["CreateNamespace=true"]
    retry = {
      limit = 5
      backoff = {
        duration    = "30s"
        factor      = 2
        maxDuration = "5m"
      }
    }
  }

  # Second source ("$values" ref) lets the chart under deploy/ consume the
  # env-specific values file under gitops/values/ from the same repo.
  argo_values_source = {
    repoURL        = var.gitops_repo_url
    targetRevision = var.gitops_revision
    ref            = "values"
  }

  argo_applications = merge(
    {
      for env, ns in local.app_envs : "backend-${env}" => {
        namespace = "argocd"
        project   = "default"
        destination = {
          server    = "https://kubernetes.default.svc"
          namespace = ns
        }
        sources = [
          {
            repoURL        = var.gitops_repo_url
            targetRevision = var.gitops_revision
            path           = "deploy/backend"
            helm = {
              releaseName = "backend"
              valueFiles  = ["$values/gitops/values/backend-${env}.yaml"]
              parameters = [
                { name = "image.repository", value = "${azurerm_container_registry.main.login_server}/backend" },
                { name = "serviceAccount.azureClientId", value = azurerm_user_assigned_identity.app.client_id },
                { name = "secretProviderClass.keyVaultName", value = azurerm_key_vault.main.name },
                { name = "secretProviderClass.tenantId", value = data.azurerm_client_config.current.tenant_id },
                # Only mount the azure-openai-* Key Vault secrets when they
                # exist (platform.tf creates them iff the key TF_VAR is set).
                { name = "secretProviderClass.aiEnabled", value = local.ai_enabled ? "true" : "false" },
              ]
            }
          },
          local.argo_values_source,
        ]
        syncPolicy = local.argo_sync_policy
      }
    },
    {
      for env, ns in local.app_envs : "frontend-${env}" => {
        namespace = "argocd"
        project   = "default"
        destination = {
          server    = "https://kubernetes.default.svc"
          namespace = ns
        }
        sources = [
          {
            repoURL        = var.gitops_repo_url
            targetRevision = var.gitops_revision
            path           = "deploy/frontend"
            helm = {
              releaseName = "frontend"
              valueFiles  = ["$values/gitops/values/frontend-${env}.yaml"]
              parameters = [
                { name = "image.repository", value = "${azurerm_container_registry.main.login_server}/frontend" },
                { name = "ingress.host", value = local.frontend_hosts[env] },
              ]
            }
          },
          local.argo_values_source,
        ]
        syncPolicy = local.argo_sync_policy
      }
    },
    {
      # ServiceMonitor + PrometheusRule + Grafana dashboard ConfigMaps.
      # observability/kustomization.yaml wraps the dashboard JSON into
      # labeled ConfigMaps — no manual `kubectl create configmap` loop.
      observability = {
        namespace = "argocd"
        project   = "default"
        destination = {
          server    = "https://kubernetes.default.svc"
          namespace = "monitoring"
        }
        sources = [
          {
            repoURL        = var.gitops_repo_url
            targetRevision = var.gitops_revision
            path           = "observability"
          }
        ]
        syncPolicy = local.argo_sync_policy
      }
    },
    {
      # Default-deny baseline + egress allow-lists. Each manifest carries its
      # own namespace (app-staging / app-production); destination is nominal.
      networkpolicies = {
        namespace = "argocd"
        project   = "default"
        destination = {
          server    = "https://kubernetes.default.svc"
          namespace = "app-staging"
        }
        sources = [
          {
            repoURL        = var.gitops_repo_url
            targetRevision = var.gitops_revision
            path           = "security"
            directory      = { include = "networkpolicies.yaml" }
          }
        ]
        syncPolicy = local.argo_sync_policy
      }
    },
    {
      # Kyverno ClusterPolicies are cluster-scoped; destination ns is nominal.
      kyverno-policies = {
        namespace = "argocd"
        project   = "default"
        destination = {
          server    = "https://kubernetes.default.svc"
          namespace = "kyverno"
        }
        sources = [
          {
            repoURL        = var.gitops_repo_url
            targetRevision = var.gitops_revision
            path           = "gitops/policies"
            directory      = { recurse = true }
          }
        ]
        syncPolicy = local.argo_sync_policy
      }
    }
  )
}

resource "helm_release" "argocd" {
  name             = "argocd"
  namespace        = "argocd"
  create_namespace = true
  repository       = "https://argoproj.github.io/argo-helm"
  chart            = "argo-cd"
  version          = "10.1.2"
  timeout          = 600

  values = [yamlencode({
    configs = {
      params = {
        # No public exposure: the UI is reached via `kubectl port-forward`
        # (GUIDE.md), so TLS terminates at the operator's tunnel.
        "server.insecure" = true
      }
    }
  })]

  depends_on = [azurerm_kubernetes_cluster_node_pool.user]
}

resource "helm_release" "kyverno" {
  name             = "kyverno"
  namespace        = "kyverno"
  create_namespace = true
  repository       = "https://kyverno.github.io/kyverno"
  chart            = "kyverno"
  version          = "3.8.1"
  timeout          = 600

  depends_on = [azurerm_kubernetes_cluster_node_pool.user]
}

# The Application specs themselves. Managed here (not app-of-apps in git)
# precisely because they carry the Terraform-known identifiers listed above.
resource "helm_release" "argocd_apps" {
  name       = "argocd-apps"
  namespace  = "argocd"
  repository = "https://argoproj.github.io/argo-helm"
  chart      = "argocd-apps"
  version    = "2.0.5"

  values = [yamlencode({
    applications = local.argo_applications
  })]

  # Kyverno must exist before Argo CD syncs gitops/policies/, and the
  # ServiceMonitor/PrometheusRule CRDs (installed by kube-prometheus-stack)
  # before it syncs observability/ — automated syncs stop retrying after the
  # retry budget, so don't race the CRDs.
  depends_on = [helm_release.argocd, helm_release.kyverno, helm_release.kube_prometheus_stack]
}
