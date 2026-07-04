# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Ironhack capstone: a production-shaped AKS platform. The **app layer is a deliberately thin, swappable stub** — the real deliverable is the infrastructure (Terraform), two independent CI/CD pipelines (GitHub Actions + OIDC), observability, and security. Do not add business logic, dependencies, or speculative structure to the app stubs.

## Hard constraints

- **Never run** `terraform apply`, `az login`, mutating `az`/`kubectl`/`helm` commands, or anything touching a live Azure subscription. Terraform is validated **offline only**: `terraform fmt -check`, `terraform init -backend=false`, `terraform validate`.
- **Never commit** secrets, real subscription/tenant/client GUIDs, tfstate, kubeconfig, or `.env`. Real IDs live in gitignored `terraform.tfvars` (ship changes via `terraform.tfvars.example`) and GitHub repo variables.
- The one-time cloud setup is `infra/bootstrap/bootstrap.sh` (Phase 0) — authored and documented here, but only ever **executed by a human** with Owner rights.

## The app-to-infra contract (keep stable — this is what makes the app swappable)

- Backend: port 8080; probes `/actuator/health/liveness`, `/actuator/health/readiness` (readiness includes the DB check on purpose — it end-to-end-validates Key Vault secret wiring); metrics `/actuator/prometheus`; demo endpoint `GET /api/items`. DB config only via `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` env vars (CSI-synced Key Vault secret `backend-db` in-cluster).
- Frontend: unprivileged nginx on 8080, `/healthz` probe; API base URL comes from **runtime** `env.js` written by `docker-entrypoint.d/10-runtime-env.sh` from `$VITE_API_URL` — never baked at build time (ADR-0007).
- Images: `<acr>/backend:sha-<gitsha>` and `<acr>/frontend:sha-<gitsha>`; deploys pin only immutable sha tags; `:main` is a convenience alias, never deployed (ADR-0005).

Changing any of these means updating, in lockstep: both Helm charts, both app workflows, `security/secretproviderclass-example.yaml`, and `docs/architecture.md`.

## Commands

Backend (requires JDK 21 — `JAVA_HOME` must point to one; system default may be Java 8):

```bash
cd apps/backend
./mvnw -B package                                  # build + unit tests
./mvnw test -Dtest=ItemControllerTest              # single test class
docker build -t backend:local .
```

Tests are plain unit tests (no Spring context, no DB) — keep them that way so CI stays hermetic.

Frontend:

```bash
cd apps/frontend
npm ci
npm run lint                                       # eslint (flat config)
npm run build                                      # tsc -b && vite build
docker build -t frontend:local .
```

Infra / deploy / workflows verification (all offline):

```bash
cd infra/terraform
terraform fmt -check -recursive && terraform init -backend=false && terraform validate

helm lint deploy/backend deploy/frontend
helm template backend deploy/backend \
  --set image.repository=x.azurecr.io/backend --set image.tag=sha-abc1234 \
  --set serviceAccount.azureClientId=11111111-1111-1111-1111-111111111111 \
  --set secretProviderClass.keyVaultName=kv-test \
  --set secretProviderClass.tenantId=22222222-2222-2222-2222-222222222222
helm template frontend deploy/frontend \
  --set image.repository=x.azurecr.io/frontend --set image.tag=sha-abc1234

actionlint                                         # lints .github/workflows/
k6 inspect load-test/items-load.js
```

Local full stack: `docker compose up --build` (frontend :8081, backend :8080, Postgres — prod uses Azure SQL; the app only ever sees the `SPRING_DATASOURCE_*` env vars).

## Architecture — the parts that span multiple files

**Identity chain (no secrets anywhere).** `infra/bootstrap/bootstrap.sh` creates three Entra apps federated to GitHub OIDC: platform-**ro** (Reader; `pull_request` subject → used by `infra-plan.yml`), platform-**rw** (Contributor + RBAC Administrator; federated **only** to the `production` environment → `infra-apply.yml`), and **app-ci** (AcrPush + AKS Cluster User → image push and helm deploy in both app workflows). Workflow ↔ federated-subject pairing is load-bearing: a deploy job moved out of its `environment:` block will fail OIDC exchange. Separately, pods reach Key Vault via workload identity: user-assigned identity + federated credential per namespace ServiceAccount (`keyvault.tf`) → SecretProviderClass `clientID` → CSI mount + synced Secret.

**Naming.** Every Azure resource name starts with the team slug (`thelocals`) via `local.name_prefix` in `main.tf` — several teams deploy this stack into one subscription, and ACR/Key Vault/SQL/storage names are globally unique. The bootstrap script's `TEAM` env var must stay in sync with the `team` Terraform variable, and `name_compact`'s 21-char cap exists to keep `kv-<compact>` within Key Vault's 24-char limit.

**Resource-group exception (ADR-0006).** Terraform does *not* create the app RG — bootstrap does, because CI roles are RBAC-scoped to it and the scope must pre-exist. `main.tf` uses `data "azurerm_resource_group"`. Don't "fix" this back to a resource.

**Secret flow.** Terraform generates the SQL password (`random_password`) and writes three Key Vault secrets (`sql-datasource-*`, in `sql.tf`) → backend chart's SecretProviderClass maps them to a K8s Secret with the exact `SPRING_DATASOURCE_*` key names → Deployment `envFrom`. The CSI volume mount in the Deployment is required even though the app reads env vars — secret sync only happens when the volume is mounted.

**Pipelines.** Path-filtered and independent: `apps/backend/**` and `apps/frontend/**` never trigger each other. Stage order in both: test → docker build (`load: true`, no push) → Trivy gate (HIGH/CRITICAL fails) → main-only: OIDC push of `sha-<gitsha>` → helm to `app-staging` → reviewer-gated helm to `app-production`. Helm deploys use `--atomic` (auto-rollback on failed probes) — a graded requirement, don't downgrade it to `--wait`. Infra: fmt/validate → Trivy IaC misconfiguration scan (also graded) → plan-with-PR-comment on PRs; plan+apply of the saved plan file on main behind the `production` environment. All jobs start from `permissions: {}` with per-job opt-in; keep it that way.

**Environments = namespaces.** One cluster, `app-staging` / `app-production`, identical charts, values set by CI `--set`. Workload placement: apps go to the autoscaled `user` node pool via `nodeSelector: workload=apps`; the `system` pool is tainted critical-addons-only (`aks.tf`).

**Network model.** Default-deny ingress baseline + egress allow-lists live in `security/networkpolicies.yaml` (per-namespace copies); app-specific ingress allows live in each chart's `networkpolicy.yaml`. Backend is reachable only from frontend pods, the `ingress-nginx` namespace, and `monitoring`. The single Ingress lives in the **frontend** chart and routes `/` → frontend, `/api` → backend.

**Docs discipline.** Every non-obvious decision has a numbered ADR in `docs/adr/` (0001–0007) and a summary in `docs/architecture.md`; README has a rubric→file map and `GUIDE.md` is the ordered human checklist (setup → demo). If you change a decision, update its ADR and the rubric map, not just the code. Some doc sections exist to satisfy specific graded rubric lines — state locking (`infra/bootstrap/README.md`), drift detection (`docs/runbook.md`), image signing + TLS boundary (`security/SECURITY.md`) — don't remove them as "redundant".
