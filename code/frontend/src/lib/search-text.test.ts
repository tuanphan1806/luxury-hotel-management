import { describe, expect, it } from "vitest";
import { createSearchMatcher } from "./search-text";

describe("Vietnamese search", () => {
  it("matches accentless input, uppercase Đ and decomposed accents", () => {
    expect(createSearchMatcher("  ho   boi ")("HỒ BƠI".normalize("NFD"))).toBe(true);
    expect(createSearchMatcher("dang")("Đặng")).toBe(true);
    expect(createSearchMatcher("Đà Nẵng")("da nang")).toBe(true);
  });

  it("preserves identifiers and handles missing contact information", () => {
    expect(createSearchMatcher("res-a1")(null, "RES-A12", 17)).toBe(true);
    expect(createSearchMatcher("17")(undefined, 17)).toBe(true);
    expect(createSearchMatcher("res-a2")("RES-A12", undefined)).toBe(false);
    expect(createSearchMatcher("   ")(null)).toBe(true);
  });
});
