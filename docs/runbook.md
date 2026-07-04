# Runbook

Operational procedures for the AKS platform. All commands assume
`az aks get-credentials --resource-group rg-thelocals-ironhack-prod --name aks-thelocals-ironhack-prod`
has been run by someone with cluster access.

## Deploy

Normal path — **never deploy by hand**:

1. Merge a PR touching `apps/backend/**` or `apps/frontend/**` into `main`.
2. The pipeline tests, builds, Trivy-scans, pushes `sha-<gitsha>`, deploys to
   `app-staging`, then waits for approval on the `production` environment.
3. Verify staging before approving:

   ```bash
   kubectl -n app-staging rollout status deploy/backend
   kubectl -n app-staging port-forward svc/backend 8080:8080 &
   curl -s http://localhost:8080/actuator/health/readiness   # {"status":"UP"}
   ```

4. Approve the `production` deployment in the GitHub Actions run.

Infra path: PR touching `infra/**` → review the plan comment → merge → approve
the gated apply.

## Infra drift detection

The only supported write path to Azure is `infra-apply` on main — nobody has
standing Contributor access, so drift should be rare and is always suspicious.
To detect it:

1. Open a PR touching `infra/**` (a comment-only change is enough) — the plan
   posted on the PR IS the drift report: any resource listed as changing that
   no commit touched was modified out of band.
2. Investigate via the Azure Activity Log on the resource group (who/what/when)
   before deciding direction:
   - Codify: replicate the change in Terraform and merge, or
   - Revert: merge an empty-change PR and let the gated apply reconcile
     reality back to code.

To automate this, add a `schedule:` trigger to `infra-plan.yml` that fails on a
non-empty plan (`terraform plan -detailed-exitcode`). Note this needs one extra
federated credential on platform-ro for the scheduled context
(`repo:<org>/<repo>:ref:refs/heads/main`), since its current trust only matches
`pull_request` — a deliberate least-privilege default.

## Rollback

**Automatic**: every CI deploy runs `helm upgrade --atomic` — if pods fail
their probes within the timeout, Helm rolls the release back to the previous
revision on its own and the job fails loudly. A bad image never lingers.

**Manual** — Helm keeps release history per namespace:

```bash
helm -n app-production history backend
helm -n app-production rollback backend <previous-revision> --wait
```

Equivalent (and what the history entry contains): redeploy the previous
immutable tag:

```bash
helm -n app-production upgrade backend deploy/backend --reuse-values \
  --set image.tag=sha-<previous-gitsha> --wait
```

Because tags are immutable (ADR-0005), the rolled-back pods are bit-for-bit the
previously scanned image. Infra rollback: `git revert` the infra commit → PR →
plan → gated apply.

## Secret rotation (DB credentials)

1. Rotate in the source of truth:
   - Terraform way: taint the password —
     `terraform taint random_password.sql_admin` → PR/merge → gated apply
     updates both the SQL server and the Key Vault secrets.
   - Break-glass way: reset in Azure SQL, then update the Key Vault secrets
     `sql-datasource-password` (new version) by hand; reconcile Terraform later.
2. The CSI driver re-pulls Key Vault every 2 minutes
   (`secret_rotation_interval` in `aks.tf`) and updates the synced Secret.
3. Env vars are read at process start, so restart pods to pick it up:

   ```bash
   kubectl -n app-production rollout restart deploy/backend
   ```

4. Verify: readiness returns UP (readiness includes the DB check, so a bad
   credential shows up immediately as NotReady — before traffic is affected).

## Scale

- **App, temporarily** (overrides HPA until next deploy):
  `kubectl -n app-production scale deploy/backend --replicas=6`
- **App, durably**: raise `autoscaling.maxReplicas` (or resources) in
  `deploy/backend/values.yaml` via PR.
- **Cluster**: raise `user_node_max_count` in `infra/terraform` via PR → gated
  apply. The cluster autoscaler handles the rest.

## Incident response — the four alerts

### High 5xx rate (`BackendHigh5xxRate`, critical)
1. Scope it: Grafana → Backend API dashboard — all URIs or one? One namespace or both?
2. `kubectl -n app-production logs deploy/backend --since=15m | grep -iE "error|exception" | head -50`
3. Readiness DOWN with DB errors → check SQL: Azure portal (throttling, DTU
   cap, failover) and Key Vault secret versions (mid-rotation mismatch — see
   secret rotation above).
4. Started right after a deploy → **rollback** (above); the deploy is the
   suspect until proven otherwise.

### Elevated p95 latency (`BackendHighP95Latency`, warning)
1. Platform dashboard: is the HPA already at `maxReplicas`? Are user-pool
   nodes saturated? → raise max / node count (Scale, above).
2. Not CPU? Check DB-side waits and connection-pool saturation
   (`hikaricp_connections_*` metrics on the backend dashboard datasource).
3. If load is a runaway client, apply rate limiting at the NGINX ingress
   (annotation) while investigating.

### CrashLoopBackOff (`PodCrashLooping`, critical)
1. `kubectl -n <ns> describe pod <pod>` — exit code and last state.
   `kubectl -n <ns> logs <pod> --previous | tail -50`
2. OOMKilled (137) → raise memory limits via values PR; crash on boot with
   secret/config errors → check the CSI mount events
   (`kubectl -n <ns> get events | grep -i secret`) and Key Vault access.
3. Bad image/config from a fresh deploy → rollback.

### Unschedulable pods (`PodsUnschedulable`, warning)
1. `kubectl -n <ns> get pods --field-selector=status.phase=Pending` →
   `kubectl describe pod` → the `FailedScheduling` event says why.
2. Insufficient CPU/memory → cluster autoscaler should be adding nodes; if the
   user pool is at `max_count`, raise it (Scale, above).
3. Selector/taint mismatch (e.g. missing `workload=apps` label on new pools) →
   fix the pool/values, not the pod.

## Useful one-liners

```bash
kubectl -n app-production get pods -o wide            # spread across nodes
kubectl -n app-production get hpa --watch             # live HPA during load test
kubectl top pods -n app-production                    # quick saturation check
helm -n app-production list                           # releases + revisions
kubectl -n app-production get events --sort-by=.lastTimestamp | tail -20
```
