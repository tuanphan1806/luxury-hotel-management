import { isStayWithinMaximum } from "@/lib/stay-window";

export type StaySearchValidationIssue =
  | "MISSING"
  | "INVALID"
  | "CHECK_IN_NOT_FUTURE"
  | "CHECK_OUT_NOT_AFTER_CHECK_IN"
  | "STAY_TOO_LONG"
  | "NO_GUESTS";

export const getStaySearchValidationIssue = (
  checkIn: string,
  checkOut: string,
  totalGuests: number,
  now = new Date(),
): StaySearchValidationIssue | null => {
  if (!checkIn || !checkOut) return "MISSING";

  const checkInDate = new Date(checkIn);
  const checkOutDate = new Date(checkOut);
  if (Number.isNaN(checkInDate.getTime()) || Number.isNaN(checkOutDate.getTime())) {
    return "INVALID";
  }
  if (checkInDate <= now) return "CHECK_IN_NOT_FUTURE";
  if (checkOutDate <= checkInDate) return "CHECK_OUT_NOT_AFTER_CHECK_IN";
  if (!isStayWithinMaximum(checkInDate, checkOutDate)) return "STAY_TOO_LONG";
  if (totalGuests < 1) return "NO_GUESTS";
  return null;
};
