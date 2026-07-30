export type UserIdentityField = "username" | "email" | "phone";

export const USERNAME_PATTERN = /^[A-Za-z0-9._-]{3,30}$/;

export function isValidUsername(value: string): boolean {
  return USERNAME_PATTERN.test(value.trim());
}

export function inferUserIdentityConflictField(message: string): UserIdentityField | null {
  const normalized = message.toLocaleLowerCase("vi-VN");
  if (normalized.includes("username") || normalized.includes("tên đăng nhập")) {
    return "username";
  }
  if (normalized.includes("email")) {
    return "email";
  }
  if (normalized.includes("phone") || normalized.includes("số điện thoại")) {
    return "phone";
  }
  return null;
}
