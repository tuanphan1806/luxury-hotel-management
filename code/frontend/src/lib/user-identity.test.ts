import { describe, expect, it } from "vitest";
import {
  inferUserIdentityConflictField,
  isValidUsername,
} from "./user-identity";

describe("user identity validation", () => {
  it("accepts the canonical username format and trims surrounding whitespace", () => {
    expect(isValidUsername(" guest.user_01 ")).toBe(true);
    expect(isValidUsername("ab")).toBe(false);
    expect(isValidUsername("guest@email.com")).toBe(false);
    expect(isValidUsername("khách-hàng")).toBe(false);
  });

  it("maps backend identity conflicts to the matching form field", () => {
    expect(inferUserIdentityConflictField("Tên đăng nhập đã được sử dụng")).toBe("username");
    expect(inferUserIdentityConflictField("User đã tồn tại với username: 'Admin'")).toBe("username");
    expect(inferUserIdentityConflictField("Email đã được sử dụng")).toBe("email");
    expect(inferUserIdentityConflictField("Số điện thoại đã được sử dụng")).toBe("phone");
    expect(inferUserIdentityConflictField("Dữ liệu đã thay đổi")).toBeNull();
  });
});
