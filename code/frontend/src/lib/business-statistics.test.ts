import { describe, expect, it } from "vitest";
import {
  financeWorkspaceFromQuery,
  monthToDatePreset,
  suggestedStatisticsGranularity,
  statisticsPreset,
  toDateInputValue,
} from "./business-statistics";

describe("business statistics helpers", () => {
  it("builds an inclusive rolling period", () => {
    expect(statisticsPreset(7, new Date(2026, 6, 28))).toEqual({
      from: "2026-07-22",
      to: "2026-07-28",
    });
  });

  it("builds month-to-date without UTC date drift", () => {
    expect(monthToDatePreset(new Date(2026, 6, 28, 23, 45))).toEqual({
      from: "2026-07-01",
      to: "2026-07-28",
    });
    expect(toDateInputValue(new Date(2026, 0, 5))).toBe("2026-01-05");
  });

  it("chooses a readable grouping for the selected reporting period", () => {
    expect(suggestedStatisticsGranularity({ from: "2026-07-01", to: "2026-07-30" })).toBe("day");
    expect(suggestedStatisticsGranularity({ from: "2026-01-01", to: "2026-04-30" })).toBe("week");
    expect(suggestedStatisticsGranularity({ from: "2025-01-01", to: "2026-01-01" })).toBe("month");
  });

  it("maps legacy finance links into the unified flat workspace", () => {
    expect(financeWorkspaceFromQuery(null)).toBe("overview");
    expect(financeWorkspaceFromQuery("overview")).toBe("overview");
    expect(financeWorkspaceFromQuery("operations")).toBe("cashier");
    expect(financeWorkspaceFromQuery("cashier")).toBe("cashier");
    expect(financeWorkspaceFromQuery("close")).toBe("close");
  });
});
