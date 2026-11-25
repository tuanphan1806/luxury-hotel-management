import { randomInt } from "node:crypto";
import { expect, type APIResponse } from "@playwright/test";
import { requireIsolation } from "./require-isolation";

export type QaRoom = { id: number; roomName: string; roomTypeId: number; status: string; cleaningStatus: string };
export type QaStay = { id: number; reservationCode: string; status: string };

export async function qaData<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = await response.json();
  return (body.data ?? body) as T;
}

function guest(fullName: string) {
  const digits = String(randomInt(10000000, 99999999));
  return { fullName, phone: "09" + digits, email: "qa-" + digits + "@example.invalid", idCardNumber: "QA" + digits,
    idCardType: "CCCD", nationality: "Việt Nam", isPrimary: true };
}

export async function createFolioStay(
  post: (path: string, data: unknown) => Promise<APIResponse>, chosen: QaRoom[], name: string, paid = 0, manual = false, hours = 1,
): Promise<QaStay> {
  requireIsolation();
  const customer = guest(name);
  const checkOut = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Ho_Chi_Minh", year: "numeric", month: "2-digit",
    day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" })
    .format(Date.now() + hours * 3600_000).replace(" ", "T");
  const response = await post("/api/reservations/walk-in/v2", {
    customer, checkOut, guestCount: chosen.length,
    rooms: chosen.map((room, i) => ({ roomId: room.id, guests: [i === 0 ? customer : guest("Khách QA " + (i + 1))] })),
    paymentOption: paid ? "CASH" : "UNPAID", ...(paid ? { paymentAmount: paid } : {}),
    ...(manual ? { priceOverrides: [{ roomTypeId: chosen[0].roomTypeId, newUnitPrice: 140000, reasonCode: "QA_RATE", note: "Giá đoàn QA" }] } : {}),
  });
  return (await qaData<{ reservation: QaStay }>(response)).reservation;
}
