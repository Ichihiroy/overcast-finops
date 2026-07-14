import {
  ArrowDownIcon,
  BoltIcon,
  CpuIcon,
  RouteIcon,
  ShieldIcon,
  SparkIcon,
} from "../icons";

interface Props {
  /** Scrolls to / focuses the scanner section. */
  onStart: () => void;
}

const FEATURES = [
  {
    Icon: CpuIcon,
    title: "Deterministic by design",
    body: "Every saving comes from an auditable rules engine with fixed constants — reproducible and defensible. An AI never touches the number.",
  },
  {
    Icon: SparkIcon,
    title: "AI explains, never invents",
    body: "The model only writes plain-English reasons and answers. Pull the API key and the findings and totals stay byte-for-byte identical.",
  },
  {
    Icon: RouteIcon,
    title: "From finding to fix",
    body: "Each finding ships with a concrete remediation. Generate an optimized bill and export a ready-to-run checklist in one click.",
  },
  {
    Icon: ShieldIcon,
    title: "Secure & self-hosted",
    body: "Runs in your own cluster. The AI key stays server-side and is never shipped to the browser or baked into an image.",
  },
];

export function Landing({ onStart }: Props) {
  return (
    <>
      {/* ── Product hero ─────────────────────────────── */}
      <section className="lp-hero">
        <div className="lp-hero-copy">
          <span className="lp-eyebrow"></span>
          <h1 className="lp-title">
            Find the waste hiding in your <span className="hl">cloud bill</span>
            .
          </h1>
          <p className="lp-lead">
            Overcast scans an Azure usage export or AWS Cost and Usage Report
            and surfaces idle, oversized,
            and forgotten resources — with a dollar figure you can actually
            trust, because a deterministic rules engine computes every number,
            not a language model.
          </p>
          <div className="lp-cta">
            <button className="scan-btn" onClick={onStart}>
              <BoltIcon size={16} /> Scan a bill
            </button>
            <a className="lp-link" href="#features">
              See what makes it different <ArrowDownIcon size={14} />
            </a>
          </div>
        </div>

        {/* Decorative stat readout — the kind of number Overcast returns. */}
        <div className="lp-hero-card" aria-hidden="true">
          <div className="lp-card-head">
            <span className="lp-card-dot crit" /> waste.detected
          </div>
          <div className="lp-card-metric">
            $2,300<span className="cents">.42</span>
            <span className="unit">/mo</span>
          </div>
          <div className="lp-card-bars">
            <span style={{ width: "72%" }} className="idle" />
            <span style={{ width: "58%" }} className="oversized" />
            <span style={{ width: "34%" }} className="forgotten" />
          </div>
          <div className="lp-card-foot">
            25 findings · deterministic rules engine
          </div>
        </div>
      </section>

      {/* ── Differentiators ──────────────────────────── */}
      <section id="features" className="lp-features">
        <h2 className="lp-section-title">Why Overcast is different</h2>
        <div className="feature-grid">
          {FEATURES.map(({ Icon, title, body }) => (
            <article className="feature" key={title}>
              <span className="feature-icon">
                <Icon size={20} />
              </span>
              <h3>{title}</h3>
              <p>{body}</p>
            </article>
          ))}
        </div>
      </section>
    </>
  );
}
