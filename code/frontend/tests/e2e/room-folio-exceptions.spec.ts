import { randomUUID } from "node:crypto";
import { expect, test, type APIRequestContext } from "@playwright/test";
import { requireIsolation } from "../qa/require-isolation";
import { createFolioStay, qaData, type QaRoom } from "../qa/room-folio-stay";
import { settleThroughSignedWebhook, deliverSignedWebhook, sePayTransactionDate } from "../qa/sepay-fixture";

type Quote = {total: number; cashDue: number};
type BankPayment = {transactionId: string; expectedAmount: number; transferContent: string; transactionReference: string};
type Options = {occupied: {id: number}[]; services: {id: number; quantity: number; amount: number}[]; collection: {id: number; cash: number; qr: number} | null};
type Folio = {number: string; cashCollected: number; qrCollected: number; allocatedPayment: number};
type Invoice = {totalAmount: number; balanceAmount: number; netPaidAmount: number; grossPaidAmount: number;
  completedRefundAmount: number; roomFolios: Folio[]; separatelySettledAmount: number;
  payments: {transactionId: string; receivedAmount?: number; amount: number}[]};

async function desk(request: APIRequestContext, username = "admin") {
  requireIsolation();
  const api = process.env.E2E_QA_API!;
  const login = await qaData<{accessToken: string}>(await request.post(api + "/auth/login", {data: {username, password: "123456"}}));
  const headers = {Authorization: "Bearer " + login.accessToken};
  const write = (method: "POST" | "PATCH" | "PUT", path: string, data: unknown) =>
    request.fetch(api + path, {method, data, headers: {...headers, "Idempotency-Key": randomUUID()}});
  return {
    read: <T>(path: string) => request.get(api + path, {headers}).then(r => qaData<T>(r)),
    post: (path: string, data: unknown) => write("POST", path, data),
    patch: (path: string, data: unknown) => write("PATCH", path, data),
    put: (path: string, data: unknown) => write("PUT", path, data),
  };
}

async function twoRoomStay(request: APIRequestContext, name: string, hours = 1) {
  const api = await desk(request);
  const rooms = (await api.read<QaRoom[]>("/api/rooms/search?status=AVAILABLE&cleaningStatus=CLEAN")).slice(0, 2);
  expect(rooms).toHaveLength(2);
  const stay = await createFolioStay(api.post, rooms, name, 0, false, hours);
  const path = "/api/reservations/" + stay.id;
  const folios = path + "/room-departures";
  const options = () => api.read<Options>(folios);
  const ids = (await options()).occupied.map(r => r.id);
  const selection = {services: [], additionalFee: 0};
  const quote = (slotIds: number[]) => api.post(folios + "/quote", {slotIds, selection, billingMode: "SEPARATE"}).then(r => qaData<Quote>(r));
  const command = (total: number) => ({billingMode: "SEPARATE", selection, expectedTotal: total, cashReceived: 0});
  const tender = (slotIds: number[], q: Quote, provider: "SEPAY" | "CASH") =>
    ({slotIds, tender: {provider, selection, expectedTotal: q.total, expectedDue: q.cashDue}});
  async function closeAndClean() {
    const balance = await api.read<{outstandingAmount: number}>(path + "/checkout-reconciliation");
    if (balance.outstandingAmount) await qaData(await api.post("/api/payments/cash", {bookingId: stay.id, purpose: "FINAL_PAYMENT"}));
    await qaData(await api.patch("/api/reservations/check-out/" + stay.id, {}));
    const bill = await api.read<Invoice>(path + "/invoice");
    expect(bill.balanceAmount).toBe(0);
    expect(bill.totalAmount).toBe(bill.netPaidAmount);
    for (const room of rooms) {
      expect(await api.read<QaRoom>("/api/rooms/" + room.id)).toMatchObject({status: "AVAILABLE", cleaningStatus: "DIRTY"});
      await qaData(await api.patch("/api/rooms/" + room.id + "/cleaning-status?cleaningStatus=CLEAN", null));
    }
    return bill;
  }
  return {api, stay, path, folios, options, ids, command, quote, tender, closeAndClean};
}

test("concurrent cash requests post once; closing a paid draft transfers its exact credit to the shared bill", async ({request}) => {
  const d = await twoRoomStay(request, "QA thu tiền đồng thời");
  const q = await d.quote(d.ids);
  const cash = d.tender(d.ids, q, "CASH");
  const offDuty = await desk(request, "staff2");
  const unpaidShift = await offDuty.post(d.folios + "/payments", cash);
  expect(unpaidShift.status()).toBe(409);
  expect((await d.options()).collection).toBeNull();

  const payments = await Promise.all([d.api.post(d.folios + "/payments", cash), d.api.post(d.folios + "/payments", cash)]);
  expect(payments.map(r => r.status()).sort()).toEqual([200, 400]);
  const active = (await d.options()).collection!;
  expect(active.cash).toBe(q.total);
  const commonCheckout = "/api/reservations/check-out/" + d.stay.id;
  expect((await d.api.patch(commonCheckout, {})).status()).toBe(400);
  expect((await d.options()).occupied).toHaveLength(2);
  const release = d.folios + "/payments/" + active.id + "/release";
  expect((await d.api.post(release, {expectedReceived: 0})).status()).toBe(400);
  await qaData(await d.api.post(release, {expectedReceived: q.total}));
  expect((await d.options()).collection).toBeNull();
  await qaData(await d.api.patch(commonCheckout + "/additional-fee", {additionalFee: 10000, reasonCode: "CLEANING", reason: "Phụ phí được ghi sau khi đóng phiếu thử"}));
  const invoice = await d.closeAndClean();
  expect(invoice.separatelySettledAmount).toBe(0);
  expect(invoice.totalAmount).toBe(q.total + 10000);
  expect(invoice.payments).toHaveLength(2);
});

for (const scenario of ["underpaid", "overpaid", "cancelled QR paid later", "expired QR paid later"] as const) {
  test("separate folio and invoice reconcile after bank transfer is " + scenario, async ({request}) => {
    test.setTimeout(150_000);
    const d = await twoRoomStay(request, "QA đối soát " + scenario);
    const ids = [d.ids[0]];
    const q = await d.quote(ids);
    const tender = d.tender(ids, q, "SEPAY");
    const [first, second] = await Promise.all([d.api.post(d.folios + "/payments", tender), d.api.post(d.folios + "/payments", tender)]);
    const payment = await qaData<BankPayment>(first);
    expect((await qaData<BankPayment>(second)).transactionId).toBe(payment.transactionId);
    const excess = scenario === "overpaid";
    const cancelled = scenario === "cancelled QR paid later";
    if (cancelled) expect((await d.api.post("/api/payments/result/" + payment.transactionId + "/abandon", {})).status()).toBe(204);
    if (scenario === "expired QR paid later") {
      await expect.poll(async () => (await d.api.read<{status: string}>("/api/payments/" + payment.transactionId)).status,
        {timeout: 110_000, intervals: [3000]}).toBe("CANCELLED");
    }
    const transferred = q.cashDue + (excess ? 10000 : scenario === "underpaid" ? -10000 : 0);
    await settleThroughSignedWebhook(request, payment, 301, transferred);
    const refunds = await d.api.read<{bookingId: number; refundId: string; refundCode: string; amount: number}[]>("/api/payments/refunds/pending");
    const relevant = refunds.filter(r => r.bookingId === d.stay.id);
    expect(relevant).toHaveLength(1);
    const refund = relevant[0];
    expect(refund.amount).toBe(excess ? 10000 : transferred);
    const checkout = {slotIds: ids, command: d.command(q.total)};
    expect((await d.api.post(d.folios, checkout)).status()).toBe(400);
    expect((await d.options()).occupied).toHaveLength(2);
    await qaData(await d.api.put("/api/payments/refunds/" + refund.refundId + "/recipient",
      {bankCode: "TPB", bankName: "TPBank", accountNumber: "1234567890", accountHolderName: "QA HOTEL"}));
    await deliverSignedWebhook(request, {id: Date.now() + 303, gateway: "TPBank", transactionDate: sePayTransactionDate(),
      accountNumber: process.env.QA_MERCHANT_BANK_ACCOUNT, content: "HOAN " + refund.refundCode, transferType: "out",
      transferAmount: refund.amount, accumulated: 0, referenceCode: "QA-FOLIO-OUT-" + randomUUID()});
    const updated = await d.quote(ids);
    expect(updated.cashDue).toBe(excess ? 0 : q.cashDue);
    if (updated.cashDue) await qaData(await d.api.post(d.folios + "/payments", d.tender(ids, updated, "CASH")));
    const paid = await qaData<Folio>(await d.api.post(d.folios, checkout));
    expect(paid.qrCollected).toBe(excess ? q.cashDue : 0);
    expect(paid.cashCollected).toBe(excess ? 0 : q.cashDue);
    const invoice = await d.closeAndClean();
    expect(invoice.roomFolios).toHaveLength(1);
    expect(invoice.separatelySettledAmount).toBe(q.total);
    expect(invoice.completedRefundAmount).toBe(refund.amount);
    expect(invoice.grossPaidAmount - invoice.completedRefundAmount).toBe(invoice.totalAmount);
    expect(invoice.payments.find(p => p.transactionId === payment.transactionId)?.receivedAmount).toBe(transferred);
  });
}

test("per-cycle service can be split one billed unit at a time across a room folio and the shared invoice", async ({request}) => {
  const d = await twoRoomStay(request, "QA dịch vụ tính theo gói", 50);
  const catalog = await d.api.read<{id: number; pricingUnit: string}[]>("/api/add-on-services?flow=IN_STAY");
  const item = catalog.find(s => s.pricingUnit === "PER_PACKAGE_CYCLE")!;
  expect(item).toBeDefined();
  const order = await qaData<{id: number; unitPrice: number; billableQuantity: number; totalPrice: number}>(
    await d.api.post(d.path + "/services", {serviceId: item.id, quantity: 1}));
  expect(order.billableQuantity).toBeGreaterThan(1);
  for (const status of ["CONFIRMED", "FULFILLED"]) await qaData(await d.api.patch(d.path + "/services/" + order.id + "/status", {status}));
  const slotIds = [d.ids[0]];
  const selection = {services: [{orderId: order.id, quantity: 1}], additionalFee: 0};
  const q = await qaData<Quote & {serviceCharge: number}>(await d.api.post(d.folios + "/quote", {slotIds, selection, billingMode: "SEPARATE"}));
  expect(q.serviceCharge).toBe(order.unitPrice);
  await qaData(await d.api.post(d.folios + "/payments", {slotIds, tender: {...d.tender(slotIds, q, "CASH").tender, selection}}));
  await qaData(await d.api.post(d.folios, {slotIds, command: {...d.command(q.total), selection}}));
  expect((await d.options()).services.find(s => s.id === order.id)).toMatchObject({quantity: order.billableQuantity - 1, amount: order.totalPrice - order.unitPrice});
  const invoice = await d.closeAndClean();
  expect(invoice.separatelySettledAmount).toBe(q.total);
});
