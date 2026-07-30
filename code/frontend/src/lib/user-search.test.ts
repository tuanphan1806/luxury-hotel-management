import { describe, expect, it } from "vitest";

import { matchesUserSearch } from "./user-search";

const user = {
  fullName: "Khách Google",
  username: "google_guest",
  email: "guest@example.com",
  phone: null,
  address: null,
};

describe("matchesUserSearch", () => {
  it("supports username and email searches without requiring contact fields", () => {
    expect(matchesUserSearch(user, "GOOGLE_GUEST")).toBe(true);
    expect(matchesUserSearch(user, "GUEST@EXAMPLE.COM")).toBe(true);
  });

  it("does not crash when OAuth users have no phone or address", () => {
    expect(matchesUserSearch(user, "0387")).toBe(false);
    expect(matchesUserSearch(user, "")).toBe(true);
  });
});
