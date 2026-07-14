import { useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import { api } from "./api";
import { CATEGORY_LABEL, moneyCents, shortType } from "./format";
import type { Category, Finding, OptimizedBill, ScanSummary } from "./types";
import { WasteCounter } from "./components/WasteCounter";
import { FindingPanel } from "./components/FindingPanel";
import { OptimizedView } from "./components/OptimizedView";
import { Landing } from "./components/Landing";
import {
  AlertIcon,
  BoltIcon,
  CategoryIcon,
  CloudIcon,
  DatabaseIcon,
  ScanIcon,
  SparkIcon,
  TerminalIcon,
} from "./icons";
import "./styles.css";

const CATEGORIES: Category[] = ["idle", "oversized", "forgotten"];
const DEMO_SCAN_ID = "demo";

type Source =
  | { kind: "sample"; scanId: string }
  | { kind: "upload"; file: File };

export default function App() {
  const [source, setSource] = useState<Source>({
    kind: "sample",
    scanId: DEMO_SCAN_ID,
  });
  const [provider, setProvider] = useState("auto");
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [summary, setSummary] = useState<ScanSummary | null>(null);
  const [findings, setFindings] = useState<Finding[]>([]);
  const [visibleCount, setVisibleCount] = useState(0); // progressive "streaming" reveal
  const [selected, setSelected] = useState<Finding | null>(null);

  const [optimized, setOptimized] = useState<OptimizedBill | null>(null);
  const [optimizing, setOptimizing] = useState(false);

  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState<string | null>(null);
  const [asking, setAsking] = useState(false);

  const revealTimer = useRef<number | null>(null);
  const scannerRef = useRef<HTMLElement | null>(null);

  function goToScanner() {
    scannerRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  // Stream findings in one-by-one for the reveal effect (whole batch ~1.2s).
  useEffect(() => {
    if (findings.length === 0) return;
    setVisibleCount(0);
    const step = Math.max(1, Math.ceil(findings.length / 24));
    revealTimer.current = window.setInterval(() => {
      setVisibleCount((n) => {
        const next = n + step;
        if (next >= findings.length && revealTimer.current !== null) {
          clearInterval(revealTimer.current);
        }
        return Math.min(next, findings.length);
      });
    }, 55);
    return () => {
      if (revealTimer.current !== null) clearInterval(revealTimer.current);
    };
  }, [findings]);

  async function runScan() {
    setScanning(true);
    setError(null);
    setOptimized(null);
    setAnswer(null);
    setSelected(null);
    try {
      let scanId: string;
      let sum: ScanSummary;
      if (source.kind === "upload") {
        const created = await api.uploadCsv(source.file, provider);
        scanId = created.scanId;
        sum = created.summary;
      } else {
        scanId = source.scanId;
        sum = await api.summary(scanId);
      }
      const page = await api.findings(scanId, 0, 200);
      setSummary(sum);
      setFindings(page.items);
    } catch (e) {
      setError((e as Error).message);
      setSummary(null);
      setFindings([]);
    } finally {
      setScanning(false);
    }
  }

  async function generateOptimized() {
    if (!summary) return;
    setOptimizing(true);
    try {
      setOptimized(await api.optimized(summary.scanId));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setOptimizing(false);
    }
  }

  async function submitQuestion(e: FormEvent) {
    e.preventDefault();
    if (!summary || !question.trim()) return;
    setAsking(true);
    setAnswer(null);
    try {
      const res = await api.ask(summary.scanId, question.trim());
      setAnswer(res.answer);
    } catch (err) {
      setAnswer(`Could not get an answer: ${(err as Error).message}`);
    } finally {
      setAsking(false);
    }
  }

  function exportChecklist() {
    if (!optimized) return;
    const lines = [
      `# Overcast remediation checklist`,
      ``,
      `Scan: ${optimized.scanId}`,
      `Current: ${moneyCents(optimized.currentMonthly, optimized.currency)}/mo`,
      `Optimized: ${moneyCents(optimized.optimizedMonthly, optimized.currency)}/mo`,
      `Savings: ${moneyCents(optimized.monthlySavings, optimized.currency)}/mo · ${moneyCents(
        optimized.annualSavings,
        optimized.currency,
      )}/yr`,
      ``,
      ...optimized.checklist.map(
        (c) =>
          `- [ ] ${c.ruleName}: ${c.action} (saves ${moneyCents(c.monthlySaving, optimized.currency)}/mo)`,
      ),
    ];
    const blob = new Blob([lines.join("\n")], { type: "text/markdown" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `overcast-checklist-${optimized.scanId}.md`;
    a.click();
    URL.revokeObjectURL(url);
  }

  const shown = useMemo(
    () => findings.slice(0, visibleCount),
    [findings, visibleCount],
  );

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark">
            <CloudIcon size={20} />
          </div>
          <div className="brand-text">
            <span className="brand-name">Overcast</span>
          </div>
        </div>
      </header>

      <main className="workspace">
        <Landing onStart={goToScanner} />

        <section className="panel scanner" id="scan" ref={scannerRef}>
          <div className="panel-head">
            <span className="ph-icon">
              <DatabaseIcon size={15} />
            </span>
            <h2>Scan a bill</h2>
          </div>
          <div className="panel-body">
            <div className="controls">
              <div className="control-group">
                <label htmlFor="src">Bill to scan</label>
                <select
                  id="src"
                  value={source.kind === "sample" ? "sample" : "upload"}
                  onChange={(e) =>
                    setSource(
                      e.target.value === "sample"
                        ? { kind: "sample", scanId: DEMO_SCAN_ID }
                        : { kind: "upload", file: new File([], "") },
                    )
                  }
                >
                  <option value="sample">
                    Sample: messy startup bill (seeded)
                  </option>
                  <option value="upload">Upload a CSV…</option>
                </select>
              </div>

              {source.kind === "upload" && (
                <>
                  <div className="control-group">
                    <label htmlFor="provider">Cloud provider</label>
                    <select
                      id="provider"
                      value={provider}
                      onChange={(e) => setProvider(e.target.value)}
                    >
                      <option value="auto">Auto-detect</option>
                      <option value="azure">Azure (usage details)</option>
                      <option value="aws">AWS (Cost & Usage Report)</option>
                      <option value="gcp">Google Cloud (detailed export)</option>
                    </select>
                  </div>
                  <div className="control-group">
                    <label htmlFor="file">Billing export CSV</label>
                    <input
                      id="file"
                      className="filebtn"
                      type="file"
                      accept=".csv,text/csv"
                      onChange={(e) => {
                        const file = e.target.files?.[0];
                        if (file) setSource({ kind: "upload", file });
                      }}
                    />
                    {source.file.name && (
                      <div className="filename">{source.file.name}</div>
                    )}
                  </div>
                </>
              )}

              <button
                className="scan-btn"
                onClick={runScan}
                disabled={
                  scanning || (source.kind === "upload" && !source.file.name)
                }
              >
                <BoltIcon size={16} />
                {scanning ? "Scanning…" : "Scan bill"}
              </button>
            </div>
          </div>
        </section>

        {error && (
          <div className="error-banner">
            <AlertIcon size={16} /> {error}
          </div>
        )}

        {summary && summary.warnings.length > 0 && (
          <div className="warn-banner">
            <AlertIcon size={16} />
            <div className="warn-body">
              <strong>Raw export detected</strong> — some rules need an enriched
              export (attachment + age data) and were skipped, so this scan
              under-reports waste. Re-export with the Overcast enricher to catch
              everything.
              <ul>
                {summary.warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            </div>
          </div>
        )}

        {summary && (
          <>
            <WasteCounter
              monthly={summary.totalMonthlyWaste}
              annual={summary.totalAnnualWaste}
              currency={summary.currency}
              wastefulCount={summary.wastefulCount ?? summary.findingCount}
              governanceCount={summary.byCategory.governance?.count ?? 0}
              totalCost={summary.totalMonthlyCost}
            />

            <div className="statgrid">
              {CATEGORIES.map((c) => (
                <div key={c} className={`stat category-${c}`}>
                  <div className="stat-label">
                    <CategoryIcon category={c} size={14} /> {CATEGORY_LABEL[c]}
                  </div>
                  <div className="stat-value">
                    {moneyCents(
                      summary.byCategory[c]?.monthlySaving ?? 0,
                      summary.currency,
                    )}
                  </div>
                  <div className="stat-foot">
                    {summary.byCategory[c]?.count ?? 0} resources · /mo
                  </div>
                </div>
              ))}
            </div>

            {optimized && (
              <OptimizedView bill={optimized} onExport={exportChecklist} />
            )}

            <section className="panel">
              <div className="panel-head">
                <span className="ph-icon">
                  <SparkIcon size={15} />
                </span>
                <h2>Ask the assistant</h2>
              </div>
              <div className="panel-body">
                <form className="ask" onSubmit={submitQuestion}>
                  <input
                    placeholder="Ask about this bill — e.g. where is my money going?"
                    value={question}
                    onChange={(e) => setQuestion(e.target.value)}
                  />
                  <button
                    className="ghost-btn"
                    type="submit"
                    disabled={asking || !question.trim()}
                  >
                    {asking ? "Asking…" : "Ask"}
                  </button>
                </form>
                {answer && (
                  <div className="ask-answer">
                    <SparkIcon size={16} />
                    <span>{answer}</span>
                  </div>
                )}
              </div>
            </section>

            <section className="panel">
              <div className="panel-head">
                <span className="ph-icon">
                  <ScanIcon size={15} />
                </span>
                <h2>Findings</h2>
                <span className="count">
                  [{shown.length}/{findings.length}]
                </span>
                <div className="panel-tools">
                  <button
                    className="ghost-btn"
                    onClick={generateOptimized}
                    disabled={optimizing}
                  >
                    <TerminalIcon size={14} />
                    {optimizing ? "Generating…" : "Generate optimized bill"}
                  </button>
                </div>
              </div>
              <div className="panel-body no-pad">
                <div className="ftable" role="table" aria-label="Findings">
                  <div className="ft-head" role="row">
                    <span className="ft-c-res">Resource</span>
                    <span className="ft-c-cat">Category</span>
                    <span className="ft-c-grp">Resource group</span>
                    <span className="ft-c-num">Cost /mo</span>
                    <span className="ft-c-num">Saving /mo</span>
                  </div>
                  {shown.map((f) => (
                    <button
                      key={f.id}
                      className={`ft-row category-${f.category}`}
                      onClick={() => setSelected(f)}
                      role="row"
                    >
                      <span className="ft-res">
                        <span className="ft-dot" aria-hidden="true" />
                        <span className="ft-res-text">
                          <span className="ft-name">{f.resourceName}</span>
                          <span className="ft-type">{shortType(f.resourceType)}</span>
                        </span>
                      </span>
                      <span className="ft-cat">
                        <span className="badge">{CATEGORY_LABEL[f.category]}</span>
                      </span>
                      <span className="ft-grp" title={f.resourceGroup}>
                        {f.resourceGroup || "—"}
                      </span>
                      <span className="ft-num cost">
                        {moneyCents(f.monthlyCost, summary.currency)}
                      </span>
                      <span className="ft-num save">
                        {f.monthlySaving > 0 ? (
                          moneyCents(f.monthlySaving, summary.currency)
                        ) : (
                          <span className="flag-zero">flag</span>
                        )}
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            </section>
          </>
        )}

        {!summary && !scanning && (
          <div className="empty">
            <ScanIcon size={30} />
            <div>
              Pick the seeded sample bill and hit <strong>Scan bill</strong> to
              see the waste.
            </div>
          </div>
        )}
      </main>

      {selected && summary && (
        <FindingPanel
          finding={selected}
          currency={summary.currency}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}
