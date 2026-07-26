import { createHmac, randomUUID } from "node:crypto";
import { expect, test, type APIRequestContext } from "@playwright/test";

const QA_API = process.env.E2E_QA_API || "http://localhost:18080";
const DEMO_PASSWORD = "123456";
const WEBHOOK_SECRET = process.env.QA_SEPAY_WEBHOOK_SECRET?.trim() ?? "";
const MERCHANT_ACCOUNT = process.env.QA_MERCHANT_BANK_ACCOUNT?.trim() ?? "";

type ApiEnvelope<T> = {
  data: T;
  message?: string;
};

type Availability = {
  roomTypeId: number;
  availableRooms: number;
  maxGuestsPerRoom: number;
};

type Reservation = {
  id: number;
  reservationCode: string;
  status: string;
  totalAmount: number;
  paymentPlan: "DEPOSIT_50" | "PREPAY_100";
  requiredInitialPayment: number;
};

type Payment = {
  transactionId: string;
  transactionReference: string;
  transferContent: string;
  status: string;
  purpose: string;
  expectedAmount: number;
  acceptedAmount?: number;
  receivedAmount?: number;
  refundRequiredAmount?: number;
};

type Refund = {
  refundId: string;
  bookingId: number;
  refundChannel: string;
  status: string;
  sourceType: string;
  amount: number;
  refundCode: string;
  recipientRequired: boolean;
};

type ManualRefundDetails = {
  refundId: string;
  amount: number;
  expectedAmount: number;
  refundCode: string;
  status: string;
  transferContent: string;
  awaitingBankConfirmation: boolean;
  bankReferenceCode?: string;
};

function qaUrl(path: string) {
  return `${QA_API}${path}`;
}

function hotelLocalDateTime(daysFromNow: number, hour: number, minute: number) {
  const date = new Date(Date.now() + daysFromNow * 24 * 60 * 60 * 1000);
  const parts = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
  return `${parts}T${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00`;
}

function sePayTransactionDate() {
  return new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).format(new Date()).replace("T", " ");
}

async function login(request: APIRequestContext, username: string) {
  const response = await request.post(qaUrl("/auth/login"), {
    data: { username, password: DEMO_PASSWORD },
  });
  expect(response.status(), await response.text()).toBe(200);
  const payload = await response.json() as { accessToken?: string };
  expect(payload.accessToken).toBeTruthy();
  return payload.accessToken!;
}

async function findAvailability(
  request: APIRequestContext,
  checkIn: string,
  checkOut: string,
) {
  const response = await request.get(qaUrl("/api/reservations/availability"), {
    params: { checkIn, checkOut },
  });
  expect(response.status(), await response.text()).toBe(200);
  const available = (await response.json() as ApiEnvelope<Availability[]>).data
    .find((item) => item.availableRooms > 0);
  expect(available, "QA seed phải có ít nhất một loại phòng trống").toBeTruthy();
  return available!;
}

async function createReservation(
  request: APIRequestContext,
  token: string,
  plan: "DEPOSIT_50" | "PREPAY_100",
  checkIn: string,
  checkOut: string,
  roomTypeId: number,
  quantity = 1,
) {
  const response = await request.post(qaUrl("/api/reservations"), {
    headers: {
      Authorization: `Bearer ${token}`,
      "Idempotency-Key": randomUUID(),
    },
    data: {
      checkIn,
      checkOut,
      guestCount: 1,
      note: `QA online ${plan} ${randomUUID().slice(0, 8)}`,
      paymentPlan: plan,
      roomTypes: [{ roomTypeId, quantity }],
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json() as ApiEnvelope<Reservation>).data;
}

async function createDepositQr(
  request: APIRequestContext,
  token: string,
  reservationId: number,
  idempotencyKey = randomUUID(),
) {
  const response = await request.post(qaUrl("/api/payments/create"), {
    headers: {
      Authorization: `Bearer ${token}`,
      "Idempotency-Key": idempotencyKey,
    },
    data: {
      bookingId: reservationId,
      provider: "SEPAY",
      purpose: "DEPOSIT",
      orderInfo: `QA SePay deposit ${reservationId}`,
    },
  });
  return { response, idempotencyKey };
}

function signedWebhook(rawBody: string) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const digest = createHmac("sha256", WEBHOOK_SECRET)
    .update(`${timestamp}.${rawBody}`)
    .digest("hex");
  return {
    "Content-Type": "application/json",
    "X-SePay-Timestamp": timestamp,
    "X-SePay-Signature": `sha256=${digest}`,
  };
}

async function deliverSignedWebhook(
  request: APIRequestContext,
  payload: Record<string, unknown>,
  rejectInvalidFirst = false,
) {
  const rawBody = JSON.stringify(payload);
  if (rejectInvalidFirst) {
    const rejected = await request.post(qaUrl("/api/payments/sepay/webhook"), {
      headers: {
        "Content-Type": "application/json",
        "X-SePay-Timestamp": Math.floor(Date.now() / 1000).toString(),
        "X-SePay-Signature": "sha256=invalid",
      },
      data: rawBody,
    });
    expect(rejected.status()).toBe(401);
  }

  const accepted = await request.post(qaUrl("/api/payments/sepay/webhook"), {
    headers: signedWebhook(rawBody),
    data: rawBody,
  });
  expect(accepted.status(), await accepted.text()).toBe(200);
  expect(await accepted.json()).toEqual({ success: true });

  const replay = await request.post(qaUrl("/api/payments/sepay/webhook"), {
    headers: signedWebhook(rawBody),
    data: rawBody,
  });
  expect(replay.status(), await replay.text()).toBe(200);
  expect(await replay.json()).toEqual({ success: true });
}

async function settleThroughSignedWebhook(
  request: APIRequestContext,
  payment: Payment,
  eventOffset: number,
  transferAmount = Number(payment.expectedAmount),
) {
  await deliverSignedWebhook(request, {
    id: Date.now() + eventOffset,
    gateway: "TPBank",
    transactionDate: sePayTransactionDate(),
    accountNumber: MERCHANT_ACCOUNT,
    code: null,
    content: payment.transferContent || payment.transactionReference,
    transferType: "in",
    transferAmount,
    accumulated: 0,
    subAccount: null,
    referenceCode: `QA-SEPAY-${Date.now()}-${eventOffset}`,
  }, true);
}

test.describe("isolated online reservation, RoomHold and signed SePay webhook", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeEach(({}, testInfo) => {
    test.skip(testInfo.project.name !== "desktop-chrome", "The isolated QA backend is exercised once.");
  });

  test("DEPOSIT_50 and PREPAY_100 preserve hold, payment and staff-confirm ordering", async ({ request }) => {
    expect(WEBHOOK_SECRET, "QA_SEPAY_WEBHOOK_SECRET phải được nạp từ backend .env").not.toBe("");
    expect(MERCHANT_ACCOUNT, "QA_MERCHANT_BANK_ACCOUNT phải được nạp từ backend .env").not.toBe("");

    const customerToken = await login(request, "customer1");
    const staffToken = await login(request, "staff1");
    const customerHeaders = { Authorization: `Bearer ${customerToken}` };
    const staffHeaders = { Authorization: `Bearer ${staffToken}` };

    const checkIn50 = hotelLocalDateTime(12, 14, 17);
    const checkOut50 = hotelLocalDateTime(12, 18, 43);
    const availability50 = await findAvailability(request, checkIn50, checkOut50);

    // Hai reservation cùng khoảng giờ đều tạo được trước khi có QR vì chưa khóa tồn kho.
    const deposit50 = await createReservation(
      request,
      customerToken,
      "DEPOSIT_50",
      checkIn50,
      checkOut50,
      availability50.roomTypeId,
      availability50.availableRooms,
    );
    const competing = await createReservation(
      request,
      customerToken,
      "DEPOSIT_50",
      checkIn50,
      checkOut50,
      availability50.roomTypeId,
      availability50.availableRooms,
    );
    expect(deposit50.status).toBe("PAYMENT_PENDING");
    expect(competing.status).toBe("PAYMENT_PENDING");
    expect(Number(deposit50.requiredInitialPayment)).toBe(
      Math.ceil(Number(deposit50.totalAmount) * 0.5),
    );

    const firstQr = await createDepositQr(request, customerToken, deposit50.id);
    expect(firstQr.response.status(), await firstQr.response.text()).toBe(200);
    const firstPayment = await firstQr.response.json() as Payment;
    expect(firstPayment.status).toBe("PENDING");
    expect(firstPayment.purpose).toBe("DEPOSIT");
    expect(Number(firstPayment.expectedAmount)).toBe(Number(deposit50.requiredInitialPayment));
    expect(firstPayment.transferContent).toBeTruthy();

    const firstQrReplay = await createDepositQr(
      request,
      customerToken,
      deposit50.id,
      firstQr.idempotencyKey,
    );
    expect(firstQrReplay.response.status()).toBe(200);
    expect((await firstQrReplay.response.json() as Payment).transactionId)
      .toBe(firstPayment.transactionId);

    // QR thứ nhất đã giữ toàn bộ tồn kho; QR cạnh tranh phải bị chặn.
    const competingQr = await createDepositQr(request, customerToken, competing.id);
    expect(competingQr.response.status()).toBeGreaterThanOrEqual(400);
    expect(competingQr.response.status()).toBeLessThan(500);

    await settleThroughSignedWebhook(request, firstPayment, 11);

    const paid50Response = await request.get(
      qaUrl(`/api/reservations/${deposit50.id}`),
      { headers: customerHeaders },
    );
    expect(paid50Response.status()).toBe(200);
    expect((await paid50Response.json() as ApiEnvelope<Reservation>).data.status).toBe("DRAFT");

    const payments50Response = await request.get(
      qaUrl(`/api/payments/booking/${deposit50.id}`),
      { headers: customerHeaders },
    );
    expect(payments50Response.status()).toBe(200);
    const payments50 = await payments50Response.json() as Payment[];
    expect(payments50).toHaveLength(1);
    expect(payments50[0].status).toBe("SUCCESS");
    expect(Number(payments50[0].acceptedAmount)).toBe(Number(firstPayment.expectedAmount));

    const confirm50 = await request.patch(
      qaUrl(`/api/reservations/confirm/${deposit50.id}`),
      {
        headers: {
          ...staffHeaders,
          "Idempotency-Key": randomUUID(),
        },
      },
    );
    expect(confirm50.status(), await confirm50.text()).toBe(200);
    expect((await confirm50.json() as ApiEnvelope<Reservation>).data.status).toBe("CONFIRMED");

    const checkIn100 = hotelLocalDateTime(14, 9, 11);
    const checkOut100 = hotelLocalDateTime(14, 13, 29);
    const availability100 = await findAvailability(request, checkIn100, checkOut100);
    const prepay100 = await createReservation(
      request,
      customerToken,
      "PREPAY_100",
      checkIn100,
      checkOut100,
      availability100.roomTypeId,
    );
    expect(prepay100.status).toBe("PAYMENT_PENDING");
    expect(Number(prepay100.requiredInitialPayment)).toBe(Number(prepay100.totalAmount));

    const fullQr = await createDepositQr(request, customerToken, prepay100.id);
    expect(fullQr.response.status(), await fullQr.response.text()).toBe(200);
    const fullPayment = await fullQr.response.json() as Payment;
    expect(Number(fullPayment.expectedAmount)).toBe(Number(prepay100.totalAmount));

    await settleThroughSignedWebhook(request, fullPayment, 22);

    const paid100Response = await request.get(
      qaUrl(`/api/reservations/${prepay100.id}`),
      { headers: customerHeaders },
    );
    expect(paid100Response.status()).toBe(200);
    expect((await paid100Response.json() as ApiEnvelope<Reservation>).data.status).toBe("DRAFT");

    const confirm100 = await request.patch(
      qaUrl(`/api/reservations/confirm/${prepay100.id}`),
      {
        headers: {
          ...staffHeaders,
          "Idempotency-Key": randomUUID(),
        },
      },
    );
    expect(confirm100.status(), await confirm100.text()).toBe(200);
    expect((await confirm100.json() as ApiEnvelope<Reservation>).data.status).toBe("CONFIRMED");
  });

  test("underpayment cancels immediately; overpayment refunds only the excess; outgoing webhook completes each refund once", async ({ request }) => {
    expect(WEBHOOK_SECRET).not.toBe("");
    expect(MERCHANT_ACCOUNT).not.toBe("");

    const customerToken = await login(request, "customer1");
    const staffToken = await login(request, "staff1");
    const adminToken = await login(request, "admin");
    const customerHeaders = { Authorization: `Bearer ${customerToken}` };
    const staffHeaders = { Authorization: `Bearer ${staffToken}` };
    const adminHeaders = { Authorization: `Bearer ${adminToken}` };

    const refundRecipient = {
      bankCode: "TPB",
      bankName: "TPBank",
      accountNumber: "1234567890",
      accountHolderName: "KHACH QA REFUND",
    };

    const underCheckIn = hotelLocalDateTime(18, 8, 13);
    const underCheckOut = hotelLocalDateTime(18, 12, 37);
    const underAvailability = await findAvailability(request, underCheckIn, underCheckOut);
    const underReservation = await createReservation(
      request,
      customerToken,
      "DEPOSIT_50",
      underCheckIn,
      underCheckOut,
      underAvailability.roomTypeId,
    );
    const underQrResult = await createDepositQr(request, customerToken, underReservation.id);
    expect(underQrResult.response.status(), await underQrResult.response.text()).toBe(200);
    const underPayment = await underQrResult.response.json() as Payment;
    const capturedUnderpayment = Number(underPayment.expectedAmount) - 1;
    expect(capturedUnderpayment).toBeGreaterThan(0);

    await settleThroughSignedWebhook(request, underPayment, 101, capturedUnderpayment);

    const cancelledResponse = await request.get(
      qaUrl(`/api/reservations/${underReservation.id}`),
      { headers: customerHeaders },
    );
    expect(cancelledResponse.status()).toBe(200);
    expect((await cancelledResponse.json() as ApiEnvelope<Reservation>).data.status).toBe("CANCELLED");

    const underLedgerResponse = await request.get(
      qaUrl(`/api/payments/booking/${underReservation.id}`),
      { headers: customerHeaders },
    );
    expect(underLedgerResponse.status(), await underLedgerResponse.text()).toBe(200);
    const underLedger = await underLedgerResponse.json() as Payment[];
    expect(underLedger).toHaveLength(1);
    expect(Number(underLedger[0].acceptedAmount)).toBe(0);
    expect(Number(underLedger[0].receivedAmount)).toBe(capturedUnderpayment);
    expect(Number(underLedger[0].refundRequiredAmount)).toBe(capturedUnderpayment);

    const pendingAfterUnder = await request.get(
      qaUrl("/api/payments/refunds/pending"),
      { headers: adminHeaders },
    );
    expect(pendingAfterUnder.status()).toBe(200);
    const underRefund = (await pendingAfterUnder.json() as Refund[])
      .find((item) => item.bookingId === underReservation.id);
    expect(underRefund).toBeTruthy();
    expect(underRefund!.refundChannel).toBe("MANUAL_BANK_TRANSFER");
    expect(underRefund!.status).toBe("AWAITING_CUSTOMER_INFO");
    expect(underRefund!.sourceType).toBe("UNACCEPTED_PAYMENT");
    expect(Number(underRefund!.amount)).toBe(capturedUnderpayment);
    expect(underRefund!.recipientRequired).toBe(true);

    const underRecipient = await request.put(
      qaUrl(`/api/payments/refunds/${underRefund!.refundId}/recipient`),
      {
        headers: {
          ...customerHeaders,
          "Idempotency-Key": randomUUID(),
        },
        data: refundRecipient,
      },
    );
    expect(underRecipient.status(), await underRecipient.text()).toBe(200);

    const underDetailsResponse = await request.get(
      qaUrl(`/api/payments/refund/${underRefund!.refundId}/manual-details`),
      { headers: staffHeaders },
    );
    expect(underDetailsResponse.status(), await underDetailsResponse.text()).toBe(200);
    const underDetails = await underDetailsResponse.json() as ManualRefundDetails;
    expect(underDetails.status).toBe("REQUESTED");
    expect(underDetails.refundCode).toBe(underRefund!.refundCode);
    expect(Number(underDetails.expectedAmount)).toBe(capturedUnderpayment);
    expect(underDetails.transferContent).toContain(underDetails.refundCode);
    expect(underDetails.awaitingBankConfirmation).toBe(true);

    await deliverSignedWebhook(request, {
      id: Date.now() + 102,
      gateway: "TPBank",
      transactionDate: sePayTransactionDate(),
      accountNumber: MERCHANT_ACCOUNT,
      content: `HOAN ${underDetails.refundCode}`,
      transferType: "out",
      transferAmount: capturedUnderpayment,
      accumulated: 0,
      referenceCode: `QA-OUT-UNDER-${Date.now()}`,
    });

    const completedUnderResponse = await request.get(
      qaUrl(`/api/payments/refund/${underRefund!.refundId}/manual-details`),
      { headers: staffHeaders },
    );
    expect(completedUnderResponse.status()).toBe(200);
    const completedUnder = await completedUnderResponse.json() as ManualRefundDetails;
    expect(completedUnder.status).toBe("SUCCEEDED");
    expect(completedUnder.bankReferenceCode).toBeTruthy();

    const overCheckIn = hotelLocalDateTime(20, 15, 7);
    const overCheckOut = hotelLocalDateTime(20, 19, 31);
    const overAvailability = await findAvailability(request, overCheckIn, overCheckOut);
    const overReservation = await createReservation(
      request,
      customerToken,
      "DEPOSIT_50",
      overCheckIn,
      overCheckOut,
      overAvailability.roomTypeId,
    );
    const overQrResult = await createDepositQr(request, customerToken, overReservation.id);
    expect(overQrResult.response.status(), await overQrResult.response.text()).toBe(200);
    const overPayment = await overQrResult.response.json() as Payment;
    const excess = 12_345;

    await settleThroughSignedWebhook(
      request,
      overPayment,
      201,
      Number(overPayment.expectedAmount) + excess,
    );

    const overDraftResponse = await request.get(
      qaUrl(`/api/reservations/${overReservation.id}`),
      { headers: customerHeaders },
    );
    expect(overDraftResponse.status()).toBe(200);
    expect((await overDraftResponse.json() as ApiEnvelope<Reservation>).data.status).toBe("DRAFT");

    const pendingAfterOver = await request.get(
      qaUrl("/api/payments/refunds/pending"),
      { headers: adminHeaders },
    );
    expect(pendingAfterOver.status()).toBe(200);
    const overRefund = (await pendingAfterOver.json() as Refund[])
      .find((item) => item.bookingId === overReservation.id);
    expect(overRefund).toBeTruthy();
    expect(overRefund!.status).toBe("AWAITING_CUSTOMER_INFO");
    expect(overRefund!.sourceType).toBe("CHECKOUT_OVERPAYMENT");
    expect(Number(overRefund!.amount)).toBe(excess);

    const overRecipient = await request.put(
      qaUrl(`/api/payments/refunds/${overRefund!.refundId}/recipient`),
      {
        headers: {
          ...customerHeaders,
          "Idempotency-Key": randomUUID(),
        },
        data: refundRecipient,
      },
    );
    expect(overRecipient.status(), await overRecipient.text()).toBe(200);

    const overDetailsResponse = await request.get(
      qaUrl(`/api/payments/refund/${overRefund!.refundId}/manual-details`),
      { headers: staffHeaders },
    );
    expect(overDetailsResponse.status()).toBe(200);
    const overDetails = await overDetailsResponse.json() as ManualRefundDetails;
    expect(overDetails.status).toBe("REQUESTED");
    expect(Number(overDetails.expectedAmount)).toBe(excess);

    // Đúng mã nhưng sai số tiền chỉ vào review, không được chốt refund.
    await deliverSignedWebhook(request, {
      id: Date.now() + 202,
      gateway: "TPBank",
      transactionDate: sePayTransactionDate(),
      accountNumber: MERCHANT_ACCOUNT,
      content: `HOAN ${overDetails.refundCode}`,
      transferType: "out",
      transferAmount: excess - 1,
      accumulated: 0,
      referenceCode: `QA-OUT-WRONG-${Date.now()}`,
    });
    const stillPendingResponse = await request.get(
      qaUrl(`/api/payments/refund/${overRefund!.refundId}/manual-details`),
      { headers: staffHeaders },
    );
    expect(stillPendingResponse.status()).toBe(200);
    expect((await stillPendingResponse.json() as ManualRefundDetails).status).toBe("REQUESTED");

    await deliverSignedWebhook(request, {
      id: Date.now() + 203,
      gateway: "TPBank",
      transactionDate: sePayTransactionDate(),
      accountNumber: MERCHANT_ACCOUNT,
      content: `HOAN ${overDetails.refundCode}`,
      transferType: "out",
      transferAmount: excess,
      accumulated: 0,
      referenceCode: `QA-OUT-OVER-${Date.now()}`,
    });

    const completedOverResponse = await request.get(
      qaUrl(`/api/payments/refund/${overRefund!.refundId}/manual-details`),
      { headers: staffHeaders },
    );
    expect(completedOverResponse.status()).toBe(200);
    expect((await completedOverResponse.json() as ManualRefundDetails).status).toBe("SUCCEEDED");

    const overLedgerResponse = await request.get(
      qaUrl(`/api/payments/booking/${overReservation.id}`),
      { headers: customerHeaders },
    );
    expect(overLedgerResponse.status()).toBe(200);
    const overLedger = await overLedgerResponse.json() as Payment[];
    expect(overLedger).toHaveLength(1);
    expect(overLedger[0].status).toBe("SUCCESS");
    expect(Number(overLedger[0].acceptedAmount)).toBe(Number(overPayment.expectedAmount));
    expect(Number(overLedger[0].receivedAmount))
      .toBe(Number(overPayment.expectedAmount) + excess);
    expect(Number(overLedger[0].refundRequiredAmount)).toBe(excess);

    const confirmOver = await request.patch(
      qaUrl(`/api/reservations/confirm/${overReservation.id}`),
      {
        headers: {
          ...staffHeaders,
          "Idempotency-Key": randomUUID(),
        },
      },
    );
    expect(confirmOver.status(), await confirmOver.text()).toBe(200);
    expect((await confirmOver.json() as ApiEnvelope<Reservation>).data.status).toBe("CONFIRMED");
  });
});
