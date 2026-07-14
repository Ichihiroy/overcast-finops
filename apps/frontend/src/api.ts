import { API_URL } from "./env";
import type {
  AskResponse,
  ExplainResponse,
  FindingsPage,
  OptimizedBill,
  ScanCreated,
  ScanSummary,
} from "./types";

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const body = (await res.json()) as { error?: string };
      if (body.error) message = body.error;
    } catch {
      /* non-JSON error body — keep the status message */
    }
    throw new Error(message);
  }
  return res.json() as Promise<T>;
}

export const api = {
  /** Upload a CSV; returns the new scan id + summary. */
  async uploadCsv(file: File, provider?: string): Promise<ScanCreated> {
    const form = new FormData();
    form.append("file", file);
    if (provider && provider !== "auto") form.append("provider", provider);
    return json(await fetch(`${API_URL}/api/scans`, { method: "POST", body: form }));
  },

  async summary(scanId: string): Promise<ScanSummary> {
    return json(await fetch(`${API_URL}/api/scans/${encodeURIComponent(scanId)}/summary`));
  },

  async findings(scanId: string, page = 0, size = 200): Promise<FindingsPage> {
    const q = `?page=${page}&size=${size}`;
    return json(await fetch(`${API_URL}/api/scans/${encodeURIComponent(scanId)}/findings${q}`));
  },

  async optimized(scanId: string): Promise<OptimizedBill> {
    return json(await fetch(`${API_URL}/api/scans/${encodeURIComponent(scanId)}/optimized`));
  },

  async explain(findingId: string): Promise<ExplainResponse> {
    return json(
      await fetch(`${API_URL}/api/findings/${encodeURIComponent(findingId)}/explain`, {
        method: "POST",
      }),
    );
  },

  async ask(scanId: string, question: string): Promise<AskResponse> {
    return json(
      await fetch(`${API_URL}/api/scans/${encodeURIComponent(scanId)}/ask`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ question }),
      }),
    );
  },
};
