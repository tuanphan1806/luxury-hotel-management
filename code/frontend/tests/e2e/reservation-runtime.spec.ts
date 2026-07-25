import { randomUUID } from "node:crypto";
import { expect, test, type APIRequestContext } from "@playwright/test";

const QA_API = process.env.E2E_QA_API || "http://localhost:18080";
const DEMO_PASSWORD = "123456";

type ApiEnvelope<T> = {
  data: T;
  message?: string;
};

type Room = {
  id: number;
  roomName: string;
  status: string;
  cleaningStatus: string;
};

type Reservation = {
  id: number;
  reservationCode: string;
  status: string;
  totalAmount: number;
  checkoutAdditionalFee?: number;
};

type WalkInResponse = {
  reservationCreated: boolean;
  reservation: Reservation;
  paymentCreationStatus: string;
};

type Payment = {
  transactionId: string;
  status: string;
  purpose: string;
  acceptedAmount: number;
};

type Reconciliation = {
  reservationId: number;
  requiredAmount: number;
  acceptedAmount: number;
  outstandingAmount: number;
  status: "MATCHED" | "MISMATCH";
  blockingReasons: string[];
};

function qaUrl(path: string) {
  return `${QA_API}${path}`;
}

function localDateTimeHoursFromNow(hours: number) {
  const parts = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).format(new Date(Date.now() + hours * 60 * 60 * 1000));
  return parts.replace(" ", "T");
}

async function login(request: APIRequestContext, username: string) {
  const response = await request.post(qaUrl("/auth/login"), {
    data: { username, password: DEMO_PASSWORD },
  });
  expect(response.status()).toBe(200);
  const payload = await response.json() as { accessToken?: string };
  expect(payload.accessToken).toBeTruthy();
  return payload.accessToken!;
}

test.describe("isolated runtime reservation and cash settlement", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeEach(({}, testInfo) => {
    test.skip(testInfo.project.name !== "desktop-chrome", "The isolated QA backend is exercised once.");
  });

  test("walk-in is atomic, fees replace instead of accumulate, payment and checkout are idempotent", async ({ request }) => {
    const staffToken = await login(request, "staff1");
    const adminToken = await login(request, "admin");
    const staffHeaders = { Authorization: `Bearer ${staffToken}` };
    const adminHeaders = { Authorization: `Bearer ${adminToken}` };

    const roomsResponse = await request.get(
      qaUrl("/api/rooms/search?status=AVAILABLE&cleaningStatus=CLEAN"),
      { headers: staffHeaders },
    );
    expect(roomsResponse.status()).toBe(200);
    const availableRooms = await roomsResponse.json() as Room[];
    expect(availableRooms.length).toBeGreaterThan(0);
    const room = availableRooms[0];

    const suffix = randomUUID().slice(0, 8);
    const createKey = randomUUID();
    const createPayload = {
      customer: {
        fullName: `Khách QA ${suffix}`,
        phone: `09${Date.now().toString().slice(-8)}`,
        email: `qa-${suffix}@example.com`,
        idCardNumber: `QA${Date.now().toString().slice(-10)}`,
      },
      checkOut: localDateTimeHoursFromNow(3),
      guestCount: 1,
      note: `Isolated runtime QA ${suffix}`,
      rooms: [{
        roomId: room.id,
        guests: [{
          fullName: `Khách QA ${suffix}`,
          phone: `09${Date.now().toString().slice(-8)}`,
          email: `qa-${suffix}@example.com`,
          idCardNumber: `QA${Date.now().toString().slice(-10)}`,
          idCardType: "CCCD",
          nationality: "Việt Nam",
          isPrimary: true,
        }],
      }],
      paymentOption: "UNPAID",
    };

    const create = await request.post(qaUrl("/api/reservations/walk-in/v2"), {
      headers: { ...staffHeaders, "Idempotency-Key": createKey },
      data: createPayload,
    });
    expect(create.status(), await create.text()).toBe(201);
    const walkIn = (await create.json() as ApiEnvelope<WalkInResponse>).data;
    expect(walkIn.reservationCreated).toBe(true);
    expect(walkIn.paymentCreationStatus).toBe("NOT_REQUESTED");
    expect(walkIn.reservation.status).toBe("CHECKED_IN");
    const reservationId = walkIn.reservation.id;

    const replay = await request.post(qaUrl("/api/reservations/walk-in/v2"), {
      headers: { ...staffHeaders, "Idempotency-Key": createKey },
      data: createPayload,
    });
    expect(replay.status()).toBe(201);
    expect(((await replay.json() as ApiEnvelope<WalkInResponse>).data).reservation.id).toBe(reservationId);

    const keyReuseWithDifferentPayload = await request.post(qaUrl("/api/reservations/walk-in/v2"), {
      headers: { ...staffHeaders, "Idempotency-Key": createKey },
      data: { ...createPayload, checkOut: localDateTimeHoursFromNow(4) },
    });
    expect(keyReuseWithDifferentPayload.status()).toBeGreaterThanOrEqual(400);
    expect(keyReuseWithDifferentPayload.status()).toBeLessThan(500);

    const firstFee = await request.patch(qaUrl(`/api/reservations/check-out/${reservationId}/additional-fee`), {
      headers: { ...staffHeaders, "Idempotency-Key": randomUUID() },
      data: {
        additionalFee: 7777,
        reasonCode: "QA_RUNTIME_FEE",
        reason: "Kiểm thử chỉnh phụ phí lần đầu",
      },
    });
    expect(firstFee.status()).toBe(200);

    const replacementFee = await request.patch(qaUrl(`/api/reservations/check-out/${reservationId}/additional-fee`), {
      headers: { ...staffHeaders, "Idempotency-Key": randomUUID() },
      data: {
        additionalFee: 3333,
        reasonCode: "QA_RUNTIME_FEE_CORRECTION",
        reason: "Kiểm thử sửa phụ phí, không cộng dồn",
      },
    });
    expect(replacementFee.status()).toBe(200);
    expect(Number(((await replacementFee.json() as ApiEnvelope<Reservation>).data).checkoutAdditionalFee)).toBe(3333);

    const beforePayment = await request.get(
      qaUrl(`/api/reservations/${reservationId}/checkout-reconciliation`),
      { headers: staffHeaders },
    );
    expect(beforePayment.status()).toBe(200);
    const before = (await beforePayment.json() as ApiEnvelope<Reconciliation>).data;
    expect(before.status).toBe("MISMATCH");
    expect(before.outstandingAmount).toBeGreaterThan(0);
    expect(before.requiredAmount - before.acceptedAmount).toBe(before.outstandingAmount);
    expect(before.blockingReasons.length).toBeGreaterThan(0);

    const blockedCheckout = await request.patch(qaUrl(`/api/reservations/check-out/${reservationId}`), {
      headers: { ...staffHeaders, "Idempotency-Key": randomUUID() },
    });
    expect(blockedCheckout.status()).toBeGreaterThanOrEqual(400);
    expect(blockedCheckout.status()).toBeLessThan(500);

    const finalPaymentResponse = await request.get(
      qaUrl(`/api/reservations/${reservationId}/final-payment`),
      { headers: staffHeaders },
    );
    expect(finalPaymentResponse.status()).toBe(200);
    const finalPayment = (await finalPaymentResponse.json() as ApiEnvelope<{ remainingAmount: number; checkoutAdditionalFee: number }>).data;
    expect(finalPayment.remainingAmount).toBe(before.outstandingAmount);
    expect(Number(finalPayment.checkoutAdditionalFee)).toBe(3333);

    const paymentKey = randomUUID();
    const paymentPayload = {
      bookingId: reservationId,
      provider: "CASH",
      purpose: "FINAL_PAYMENT",
      orderInfo: `QA final payment ${suffix}`,
    };
    const paymentResponse = await request.post(qaUrl("/api/payments/cash"), {
      headers: { ...staffHeaders, "Idempotency-Key": paymentKey },
      data: paymentPayload,
    });
    expect(paymentResponse.status(), await paymentResponse.text()).toBe(200);
    const payment = await paymentResponse.json() as Payment;
    expect(payment.status).toBe("SUCCESS");
    expect(payment.purpose).toBe("FINAL_PAYMENT");
    expect(payment.acceptedAmount).toBe(before.outstandingAmount);

    const paymentReplay = await request.post(qaUrl("/api/payments/cash"), {
      headers: { ...staffHeaders, "Idempotency-Key": paymentKey },
      data: paymentPayload,
    });
    expect(paymentReplay.status()).toBe(200);
    expect((await paymentReplay.json() as Payment).transactionId).toBe(payment.transactionId);

    const duplicatePayment = await request.post(qaUrl("/api/payments/cash"), {
      headers: { ...staffHeaders, "Idempotency-Key": randomUUID() },
      data: paymentPayload,
    });
    expect(duplicatePayment.status()).toBeGreaterThanOrEqual(400);
    expect(duplicatePayment.status()).toBeLessThan(500);

    const afterPayment = await request.get(
      qaUrl(`/api/reservations/${reservationId}/checkout-reconciliation`),
      { headers: staffHeaders },
    );
    expect(afterPayment.status()).toBe(200);
    const matched = (await afterPayment.json() as ApiEnvelope<Reconciliation>).data;
    expect(matched.status).toBe("MATCHED");
    expect(matched.outstandingAmount).toBe(0);
    expect(matched.acceptedAmount).toBe(matched.requiredAmount);

    const checkoutKey = randomUUID();
    const checkout = await request.patch(qaUrl(`/api/reservations/check-out/${reservationId}`), {
      headers: { ...staffHeaders, "Idempotency-Key": checkoutKey },
    });
    expect(checkout.status(), await checkout.text()).toBe(200);
    expect(((await checkout.json() as ApiEnvelope<Reservation>).data).status).toBe("CHECKED_OUT");

    const checkoutReplay = await request.patch(qaUrl(`/api/reservations/check-out/${reservationId}`), {
      headers: { ...staffHeaders, "Idempotency-Key": checkoutKey },
    });
    expect(checkoutReplay.status()).toBe(200);
    expect(((await checkoutReplay.json() as ApiEnvelope<Reservation>).data).status).toBe("CHECKED_OUT");

    const auditBeforeInvoice = await request.get(
      qaUrl(`/api/reservations/${reservationId}/audit-logs`),
      { headers: adminHeaders },
    );
    expect(auditBeforeInvoice.status()).toBe(200);
    const auditItemsBefore = (await auditBeforeInvoice.json() as ApiEnvelope<Array<{ action: string }>>).data;
    expect(auditItemsBefore.map((item) => item.action)).toEqual(expect.arrayContaining([
      "CHECK_IN",
      "UPDATE_CHECKOUT_FEE",
      "PAYMENT_RECEIVED",
      "CHECK_OUT",
      "CHECKOUT_RECONCILIATION_PASSED",
    ]));

    const invoiceFirst = await request.get(
      qaUrl(`/api/reservations/${reservationId}/invoice`),
      { headers: staffHeaders },
    );
    const invoiceSecond = await request.get(
      qaUrl(`/api/reservations/${reservationId}/invoice`),
      { headers: staffHeaders },
    );
    expect(invoiceFirst.status()).toBe(200);
    expect(invoiceSecond.status()).toBe(200);
    const firstSnapshot = (await invoiceFirst.json() as ApiEnvelope<Record<string, unknown>>).data;
    const secondSnapshot = (await invoiceSecond.json() as ApiEnvelope<Record<string, unknown>>).data;
    expect(secondSnapshot).toEqual(firstSnapshot);
    expect(firstSnapshot.settlementStatus).toBe("PAID");

    const auditAfterInvoice = await request.get(
      qaUrl(`/api/reservations/${reservationId}/audit-logs`),
      { headers: adminHeaders },
    );
    expect((await auditAfterInvoice.json() as ApiEnvelope<unknown[]>).data).toHaveLength(auditItemsBefore.length);

    const roomAfterCheckout = await request.get(qaUrl(`/api/rooms/${room.id}`), { headers: staffHeaders });
    expect(roomAfterCheckout.status()).toBe(200);
    const releasedRoom = await roomAfterCheckout.json() as Room;
    expect(releasedRoom.status).toBe("AVAILABLE");
    expect(releasedRoom.cleaningStatus).toBe("DIRTY");
  });
});
