-- Data-quality notes for a scan (e.g. raw export missing enrichment columns).
-- Nullable: null means the export was fully enriched and every rule could run.
ALTER TABLE scan ADD data_notes NVARCHAR(1024);
