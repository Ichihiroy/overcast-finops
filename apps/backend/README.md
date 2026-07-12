# Overcast backend — cloud-bill waste scanner.

Spring Boot 3.3 / Java 21. Ingests an Azure Cost Management CSV, runs a
deterministic rules engine, and serves color-coded findings with a per-item
monthly saving, a total, and remediation. Azure OpenAI is used **only** to
phrase explanations and answer questions — never to compute a number.

## The deterministic-number guarantee (non-negotiable)

**Every dollar in the savings figure is produced by the rules engine, never by
the AI.** This is enforced structurally, not by convention:

- `RulesEngine` is the single owner of `monthly_saving` and
  `scan.total_monthly_waste`. It reads facts from the CSV and constants from
  [`rules-config.yaml`](src/main/resources/rules-config.yaml) — no AI, no
  randomness, no clock. Same CSV in, same dollars out, forever.
- Each resource contributes **at most one** saving-bearing rule (the highest),
  plus an optional `$0` untagged governance flag, so two optimizations can
  never double-count the same dollar. Invariant: `sum(savings) ≤ sum(cost)`,
  asserted in [`RulesEngineTest`](src/test/java/com/ironhack/backend/overcast/rules/RulesEngineTest.java).
- The AI layer (`AzureOpenAiClient`, `ExplanationService`) only ever returns
  **prose**. `/explain` and `/ask` hand it a `Finding` (or findings) that
  already carry the engine's numbers; the AI is instructed to quote them and
  has no code path that can write `monthly_saving`.
- The engine refuses to start unless `rules-config.yaml`'s rule set matches the
  Java implementations 1:1 — so the YAML can be audited as the authoritative
  rule list.

## Runs with NO Azure OpenAI key (CI + demo default)

The whole app builds, tests, and demos with no key present. When
`AZURE_OPENAI_ENDPOINT/API_KEY/DEPLOYMENT` are blank, `AzureOpenAiClient`
returns empty on every call and `ExplanationService` falls back to
deterministic templates (`/explain`) or a canned notice plus the top findings
(`/ask`). CI never needs a key. The key, when present, stays **server-side
only** and is never sent to the frontend.

Tests are plain unit tests — no Spring context, no DB, no key — so CI stays
hermetic:

```bash
JAVA_HOME=/path/to/jdk21 ./mvnw -B test      # 15 Overcast tests + 2 stub, all green with no key
docker build -t overcast-backend:local .     # multi-stage, skipTests in image build
```

## Data flow

```
CSV → AzureUsageCsvParser → List<NormalizedResource>
    → RulesEngine (rules-config.yaml)  ← THE money, deterministic
    → persist scan + findings (Flyway: Postgres local / Azure SQL prod)
    → summary + findings cached in Redis (the hot read path)
API:
  POST /api/scans                 multipart CSV → {scanId, summary}
  GET  /api/scans/{id}/summary    totals + by-category (Redis-cached)
  GET  /api/scans/{id}/findings   paginated, biggest-saving-first (load-test target)
  GET  /api/scans/{id}/optimized  before/after totals + remediation checklist
  POST /api/findings/{id}/explain AI phrasing OR deterministic template; cached
  POST /api/scans/{id}/ask        Q&A grounded ONLY on this scan's findings
```

`GET /api/items` from the platform stub is left in place (harmless extra
endpoint); the Overcast surface is `/api/scans` and `/api/findings`.

## Rules

| id                        | category  | fires when                                         | saving        |
| ------------------------- | --------- | -------------------------------------------------- | ------------- |
| `unattached_disk`         | forgotten | managed disk, association column present but empty | 100% of cost  |
| `orphaned_public_ip`      | forgotten | public IP, unassociated                            | 100%          |
| `old_snapshot`            | forgotten | snapshot `age_days > 90`                           | 100%          |
| `prev_gen_vm`             | oversized | VM SKU in the previous-gen list                    | 20%           |
| `nonprod_247`             | idle      | VM in dev/test/sandbox/qa RG, ran ~full month      | 65%           |
| `ondemand_vs_reserved`    | oversized | prod VM, sustained full-month usage                | 30%           |
| `premium_storage_nonprod` | oversized | attached premium disk in a non-prod RG             | 40%           |
| `untagged`                | forgotten | missing `owner`/`env` tag                          | 0 (flag only) |

Constants (`prev_gen_delta`, `offhours_factor`, `ri_discount`, `premium_delta`,
thresholds, SKU lists) all live in `rules-config.yaml`. CSV column mapping is
documented in [`docs/csv-schema.md`](../../docs/csv-schema.md).

## Seeded demo scan

On startup the app seeds scan id **`demo`** from
[`samples/azure-hero-messy.csv`](src/main/resources/samples/) — a messy startup
bill whose flagged waste totals exactly **$2,300.42/mo** (25 findings). The k6
load test and the stage demo hit `GET /api/scans/demo/findings` with zero
setup. A clean sample (`azure-small-clean.csv`, $0 waste) is included too.

## Local run

```bash
docker compose up --build     # postgres + redis + backend :8080 + frontend :8081
```

Config from env (Key Vault via CSI in-cluster): `SPRING_DATASOURCE_URL/
USERNAME/PASSWORD`, `REDIS_HOST/PORT/PASSWORD`, `AZURE_OPENAI_ENDPOINT/
API_KEY/DEPLOYMENT`. The backend is stateless (state in DB + Redis) so
replicas and the HPA are real.
