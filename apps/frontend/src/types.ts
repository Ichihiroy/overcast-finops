// Mirrors the backend DTOs (com.ironhack.backend.overcast.web.dto.Dtos).
export type Category = "idle" | "oversized" | "forgotten" | "governance";

export interface CategoryTotal {
  count: number;
  monthlySaving: number;
}

export interface ScanSummary {
  scanId: string;
  filename: string;
  currency: string;
  totalMonthlyCost: number;
  totalMonthlyWaste: number;
  totalAnnualWaste: number;
  findingCount: number;
  /**
   * Findings with actual money attached — excludes $0 governance flags.
   * Optional: absent when a not-yet-redeployed backend serves the response
   * (backend and frontend roll out independently).
   */
  wastefulCount?: number;
  byCategory: Partial<Record<Category, CategoryTotal>>;
  warnings: string[];
}

export interface Finding {
  id: string;
  resourceId: string;
  resourceName: string;
  resourceType: string;
  resourceGroup: string;
  region: string;
  ruleId: string;
  category: Category;
  monthlyCost: number;
  monthlySaving: number;
  remediation: string;
}

export interface FindingsPage {
  items: Finding[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface ScanCreated {
  scanId: string;
  summary: ScanSummary;
}

export interface ExplainResponse {
  explanation: string;
  remediation: string;
  source: "ai" | "fallback" | "cache";
}

export interface ChecklistItem {
  resourceId: string;
  resourceName: string;
  resourceGroup: string;
  ruleId: string;
  ruleName: string;
  action: string;
  monthlySaving: number;
}

export interface OptimizedBill {
  scanId: string;
  currency: string;
  currentMonthly: number;
  optimizedMonthly: number;
  monthlySavings: number;
  annualSavings: number;
  byCategory: Record<Category, CategoryTotal>;
  checklist: ChecklistItem[];
}

export interface AskResponse {
  answer: string;
  source: "ai" | "fallback" | "cache";
}
