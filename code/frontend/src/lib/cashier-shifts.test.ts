import { describe, expect, it } from "vitest";
import { movementLabel, parseWholeVnd } from "./cashier-shifts";

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
});
