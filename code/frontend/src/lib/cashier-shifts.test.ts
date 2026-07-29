import { describe, expect, it } from "vitest";
import {
  movementLabel,
  parseWholeVnd,
  suggestedOpeningCash,
  type CashierShift,
} from "./cashier-shifts";

describe("cashier shift helpers", () => {
  it("parses whole VND values with Vietnamese separators", () => {
    expect(parseWholeVnd("500.000")).toBe(500_000);
    expect(parseWholeVnd(" 1 250 000 ")).toBe(1_250_000);
  });

  it("rejects fractional, negative and non-numeric drawer values", () => {
    expect(parseWholeVnd("10,5")).toBeNull();
    expect(parseWholeVnd("-1000")).toBeNull();
    expect(parseWholeVnd("abc")).toBeNull();
  });

  it("uses operational Vietnamese movement labels", () => {
    expect(movementLabel("CASH_PAYMENT")).toBe("Thu tiền đặt phòng");
    expect(movementLabel("CASH_REFUND")).toBe("Hoàn tiền khách");
  });

  it("suggests the latest counted closing cash for the same staff workflow", () => {
    const shifts = [
      { status: "OPEN", countedCashAmount: null },
      { status: "CLOSED", countedCashAmount: 1_250_000, shiftCode: "SHIFT-NEW" },
      { status: "CLOSED", countedCashAmount: 900_000, shiftCode: "SHIFT-OLD" },
    ] as CashierShift[];

    const suggestion = suggestedOpeningCash(shifts, true);

    expect(suggestion.amount).toBe(1_250_000);
    expect(suggestion.source?.shiftCode).toBe("SHIFT-NEW");
  });

  it("does not carry another operator's cash into the ADMIN monitoring view", () => {
    const shifts = [
      { status: "CLOSED", countedCashAmount: 1_250_000, shiftCode: "SHIFT-STAFF" },
    ] as CashierShift[];

    expect(suggestedOpeningCash(shifts, false)).toEqual({ amount: 0, source: null });
  });
});
