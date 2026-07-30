import { describe, expect, it } from "vitest";
import {
  movementLabel,
} from "./cashier-shifts";

describe("cashier shift helpers", () => {
  it("uses operational Vietnamese movement labels", () => {
    expect(movementLabel("CASH_PAYMENT")).toBe("Thu tiền đặt phòng");
    expect(movementLabel("CASH_REFUND")).toBe("Hoàn tiền khách");
  });
});
