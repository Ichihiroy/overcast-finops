# CSV schema — Azure Cost Management usage details

Overcast ingests the **Azure Cost Management "usage details" CSV export**,
the **AWS CUR**, and the **GCP detailed billing export** (see the provider
sections below) and normalizes all three into one internal
model that the rules engine consumes. The UI has a provider selector; on
"Auto-detect" the header shape decides (AWS headers are `lineItem/`
namespaced, GCP headers are dotted, Azure headers are bare words). This doc
is the contract between the export and
[`AzureUsageCsvParser`](../apps/backend/src/main/java/com/ironhack/backend/overcast/csv/AzureUsageCsvParser.java);
keep the two in sync.

## How to get the file

Azure Portal → **Cost Management + Billing** → **Cost analysis** →
*Download* / *Exports* → **usage details** (CSV). Column names vary slightly by
API version, so the parser accepts several header aliases per field.

> **Wrong file trap**: the Cost analysis blade's default *Download* gives a
> **daily-totals** CSV (`UsageDate,Cost,...`) with no per-resource rows —
> nothing to scan. The parser detects this shape and says so. Group the view
> by **Resource** before downloading (or use *Usage + charges → Download
> usage*) to get the per-resource export.

The grouped-by-Resource download is also accepted even though it has no
`Quantity`/`SKU` columns: usage is then **unknown**, and the sustained-hours
rules treat a machine that billed the whole month as always-on instead of
staying silent. Its `Tags` column (a JSON array of `"key":"value"` strings)
and display-name types ("Virtual machine", via the ARM id) are handled.

## Columns

Headers are matched **case-insensitively**; the first alias found wins.

| Normalized field | Accepted headers (aliases) | Required | Notes |
| ---------------- | -------------------------- | :------: | ----- |
| `resourceId` | `ResourceId`, `InstanceId` | ✅ | Full ARM id; its last segment is the display name. Rows are grouped by this. |
| `resourceType` | `ResourceType`, `ConsumedService` | ✅ | e.g. `Microsoft.Compute/virtualMachines`. Drives resource classification. |
| `resourceGroup` | `ResourceGroup`, `ResourceGroupName` | ✅ | Used by the non-prod pattern (`dev\|test\|sandbox\|qa\|stage\|uat\|poc\|demo`); the `env` tag value is a fallback signal. |
| `cost` | `Cost`, `CostInBillingCurrency`, `PreTaxCost` | ✅ | Per-row cost; **summed** across a resource's rows. |
| `region` | `ResourceLocation`, `Location` | | |
| `meter` | `MeterName` | | Descriptive only. |
| `sku` | `SKU`, `MeterSubCategory`, `ServiceTier` | | Matched against the prev-gen SKU list and premium-storage check. |
| `quantity` | `Quantity`, `UsageQuantity` | | **Summed**; for VMs this is usage hours, compared to `sustained_hours` (500). |
| `unitPrice` | `UnitPrice`, `EffectivePrice` | | Carried for reference. |
| `currency` | `Currency`, `BillingCurrency`, `BillingCurrencyCode` | | First non-blank value becomes the scan currency (default `USD`). |
| `tags` | `Tags` | | JSON object (braces optional). Matched against `required_tags` (`owner`, `env`). |
| `associatedResource` | `AssociatedResource` | | **Enrichment** — see below. |
| `ageDays` | `AgeDays` | | **Enrichment** — integer age for snapshot rule. |

Missing any of the four required columns → HTTP 400 with a message naming the
column.

## Multi-row aggregation

A usage-details export has **one row per meter per resource per day/period**, so
a single VM appears many times. The parser groups all rows by `resourceId` and:

- **sums** `cost` and `quantity` across the group (total monthly cost / usage);
- takes descriptive fields (`resourceType`, `sku`, `region`, `tags`,
  `associatedResource`, `ageDays`) from the **primary meter** — the row with the
  highest single cost — which is the representative line for that resource.

This means `monthly_cost` in a finding is the resource's full monthly spend, not
one meter line.

## Enrichment columns (`AssociatedResource`, `AgeDays`)

Raw Azure exports do not include attachment state or resource age. Overcast's
sample generator and the documented ETL add two optional columns so the
"forgotten resource" rules can fire deterministically:

- **`AssociatedResource`** — the NIC/VM/LB a disk or public IP is attached to.
  The distinction is deliberate and load-bearing:
  - **column present, value blank** → the resource is *known to be unattached*
    → `unattached_disk` / `orphaned_public_ip` fire.
  - **column present, value set** → attached (counts as "premium disk attached"
    for `premium_storage_nonprod`).
  - **column absent entirely** → attachment *unknown*; the association-based
    rules stay silent rather than guess. (In the parser this is the
    `null` vs empty-string distinction on `associatedResource`.)
- **`AgeDays`** — integer age of a snapshot; `old_snapshot` fires when it
  exceeds `snapshot_age_days` (30). Absent/blank → rule stays silent.

Without these columns the cost/utilization rules (prev-gen, non-prod 24/7,
reserved-instance, premium storage) still work from cost, SKU, resource group,
and usage hours alone.

## Sample files

- [`azure-hero-messy.csv`](../apps/backend/src/main/resources/samples/azure-hero-messy.csv)
  — the seeded `demo` scan; flagged waste totals **$2,300.42/mo**.
- [`azure-small-clean.csv`](../apps/backend/src/main/resources/samples/azure-small-clean.csv)
  — a tidy bill that produces **zero** findings.
- [`aws-cur-messy.csv`](../apps/backend/src/main/resources/samples/aws-cur-messy.csv)
  — a small AWS CUR with an unattached volume, an old snapshot, and an idle
  Elastic IP; upload it to demo the AWS path.

## AWS CUR

AWS **Cost and Usage Report** (CUR, resource-id granularity) uploads are
supported by
[`AwsCurCsvParser`](../apps/backend/src/main/java/com/ironhack/backend/overcast/csv/AwsCurCsvParser.java),
producing the same `NormalizedResource` model — the rules engine and everything
downstream are cloud-agnostic.
[`UsageCsvParser`](../apps/backend/src/main/java/com/ironhack/backend/overcast/csv/UsageCsvParser.java)
auto-detects the provider: CUR headers are namespaced (`lineItem/...`), Azure
headers are bare words. The scan's `source_cloud` records which one matched.

| Normalized field | AWS CUR column | Required | Notes |
| ---------------- | -------------- | :------: | ----- |
| `resourceId` | `lineItem/ResourceId` | ✅ | Line items without one (RI fees, taxes, support) are dropped. |
| `resourceType` | `lineItem/ProductCode` + `lineItem/UsageType` | | Kind: `BoxUsage`→VM, `EBS:Volume`→disk, `EBS:Snapshot`→snapshot, `ElasticIP`/`IdleAddress`→public IP. |
| `resourceGroup` | `lineItem/UsageAccountId` | | AWS has no resource groups; the numeric account id never matches the non-prod name pattern, so nonprod classification falls back to the `env`/`environment` tag value. |
| `region` | `product/region` | | |
| `sku` | `product/instanceType` | | |
| `quantity` | `lineItem/UsageAmount` | | Summed per resource, like Azure. |
| `unitPrice` | `lineItem/UnblendedRate` | | |
| `cost` | `lineItem/UnblendedCost` | ✅ | Summed per resource. |
| `currency` | `lineItem/CurrencyCode` | | |
| `tags` | `resourceTags/user:*` | | One column per tag key; merged across a resource's line items. |
| `associatedResource`, `ageDays` | *(enricher-added, optional)* | | Same enrichment contract as Azure. |

The prev-gen SKU list in `rules-config.yaml` mixes exact Azure names with
prefix wildcards (`t2.*`, `m4.*`, … and GCP `n1*`), so `prev_gen_vm` fires on
previous-generation AWS instance families and GCP N1 machines too; usage- and
cost-based rules work identically.

### AWS monthly service summary

A second AWS shape is accepted: the flat monthly summary
(`invoice_month, linked_account_id, service, usage_type, region,
usage_quantity, unit, unit_cost_usd, cost_usd`). It has no resource ids, so
each `account/service/usage_type` line becomes one pseudo-resource; EC2
`BoxUsage:*` and RDS `InstanceUsage:*` lines classify as reservable compute
(the instance type after the `:` feeds the prev-gen check), and the sustained
on-demand rule fires on their 730-hour months.

## GCP detailed billing export

GCP support ingests the **Cloud Billing "detailed usage cost" export** (the
BigQuery table, flattened to CSV — its column names keep the dotted paths).
The console's cost-table download has no resource names and cannot be
scanned. Parser:
[`GcpBillingCsvParser`](../apps/backend/src/main/java/com/ironhack/backend/overcast/csv/GcpBillingCsvParser.java).

| Normalized field | GCP column | Required | Notes |
| ---------------- | ---------- | :------: | ----- |
| `resourceId` | `resource.global_name`, `resource.name` | ✅ | Rows without one (support fees, untied charges) are dropped. |
| `resourceType` | `service.description` + `sku.description` | | Kind from the SKU description: `Instance Core/Ram`→VM, `Storage PD Capacity`→disk, `…Snapshot`→snapshot, `Static/External Ip`→public IP. |
| `resourceGroup` | `project.id`, `project.name` | | The project fills the slot, so the non-prod name pattern (`dev\|test\|sandbox\|qa\|stage\|uat\|poc\|demo`) works on project ids. |
| `region` | `location.region`, `location.location` | | |
| `sku` / `meter` | `sku.description` | | |
| `quantity` | `usage.amount` | | Summed per resource. |
| `cost` | `cost` | ✅ | Summed per resource. |
| `currency` | `currency` | | |
| `tags` | `labels` | | JSON array of `{key,value}` structs (BigQuery shape) or a plain JSON object — both accepted. |
| `associatedResource`, `ageDays` | *(enricher-added, optional)* | | Same enrichment contract as Azure/AWS. |

Sample: [`gcp-billing-messy.csv`](../apps/backend/src/main/resources/samples/gcp-billing-messy.csv)
— an orphaned disk, a 300-day snapshot, an idle static IP, and a dev VM
running 24/7.
