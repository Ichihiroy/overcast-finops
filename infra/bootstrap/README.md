# Phase 0 — Bootstrap (run once, by a human)

## Why this exists (the chicken-and-egg problem)

Everything else in this repo is applied by CI. But CI cannot create:

1. **The Terraform state backend** — `terraform init` needs the storage account
   to already exist before the first plan can run.
2. **The CI identities themselves** — a GitHub Actions job can only log in to
   Azure via OIDC *after* an Entra app registration trusts the repo.
3. **The application resource group** — the CI identities get least-privilege
   roles scoped to this RG (not the subscription), and an RBAC scope must exist
   before roles can be assigned on it (see `docs/adr/0006-rg-in-bootstrap.md`).

So this script is the single, documented, manual step. Everything after it is
pull requests and pipelines.

## What it creates

All names carry the team prefix (`thelocals` by default) because several teams
deploy this stack into the same subscription — without it, globally-unique
names (storage account, and later ACR/Key Vault/SQL from Terraform) collide.

| Resource                                | Purpose                                        |
| --------------------------------------- | ---------------------------------------------- |
| `rg-<team>-<project>-tfstate` + storage | Remote Terraform state (blob container, with state locking — see below) |
| `rg-<team>-<project>-<env>`             | App RG — the RBAC scope for all CI roles       |
| `<prefix>-gh-platform-ro`         | OIDC identity: `terraform plan` on PRs (Reader)|
| `<prefix>-gh-platform-rw`         | OIDC identity: `terraform apply` on main (Contributor + RBAC Administrator, gated by the `production` environment) |
| `<prefix>-gh-app-ci`              | OIDC identity: ACR push + Helm deploy (AcrPush + AKS Cluster User) |

Each identity is federated to this GitHub repo with subject claims matching how
the workflows run: `pull_request` for RO, `environment:production` for RW,
`ref:refs/heads/main` + `environment:staging|production` for app CI. No client
secrets exist anywhere — GitHub presents a short-lived OIDC token and Azure
exchanges it.

### Remote state & locking

Terraform state lives in the blob container (`azurerm` backend, config injected
at `terraform init` via `-backend-config` — see the workflows). The backend
**locks state automatically using Azure blob leases**: any `plan`/`apply`
acquires a lease on the state blob, so two concurrent runs (or a runner racing
a laptop) cannot corrupt state — the second run fails fast with a lock error.
This is why both CI identities get *Storage Blob Data Contributor* on the
storage account: even a read-only plan must acquire the lease. The
`infra-apply` workflow additionally serializes itself with a non-cancelable
GitHub concurrency group.

Why RBAC Administrator on top of Contributor: Terraform itself creates role
assignments (AcrPull for the kubelet, Key Vault roles for the workload
identity), and Contributor is not allowed to grant roles. Scoping it to the app
RG keeps it far from subscription-wide Owner.

## How to run

```bash
az login   # a user with Owner on the subscription
SUBSCRIPTION_ID=<sub-id> GITHUB_REPO=<org>/<repo> ./bootstrap.sh
```

Optional: `LOCATION` (default `westeurope`), `TEAM` (`thelocals`), `PROJECT`
(`ironhack`), `ENVIRONMENT` (`prod`). `TEAM` must match the `team` Terraform
variable. The script is idempotent — safe to re-run.

## After running

1. Copy the printed values into **GitHub repo → Settings → Secrets and
   variables → Actions → Variables** (they are identifiers, not secrets).
2. Create GitHub **environments** `staging` and `production`; add a **required
   reviewer** on `production` (this is the manual gate for infra apply and prod
   deploys).
3. Open a PR touching `infra/**` → `infra-plan.yml` runs as platform-ro.
4. Merge → `infra-apply.yml` waits for the production-environment approval,
   then applies as platform-rw.
5. Set the post-apply variables (`ACR_LOGIN_SERVER`, `AKS_NAME`,
   `APP_IDENTITY_CLIENT_ID`, `KEY_VAULT_NAME`) from `terraform output`.
