import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { expect, test, type APIRequestContext } from "@playwright/test";
import { requireIsolation } from "../qa/require-isolation";
import { createFolioStay, qaData, type QaRoom } from "../qa/room-folio-stay";
import { settleThroughSignedWebhook, deliverSignedWebhook, sePayTransactionDate } from "../qa/sepay-fixture";

type Quote = {total: number; cashDue: number};
type BankPayment = {transactionId: string; expectedAmount: number; transferContent: string; transactionReference: string};
type Options = {occupied: {id: number}[]; services: {id: number; quantity: number; amount: number}[]; collection: {id: number; cash: number; qr: number; pendingAmount: number | null} | null};
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

async function folioStay(request: APIRequestContext, name: string, hours = 1, count = 2, paid = 0) {
  const api = await desk(request);
  const rooms = (await api.read<QaRoom[]>("/api/rooms/search?status=AVAILABLE&cleaningStatus=CLEAN")).slice(0, count);
  expect(rooms).toHaveLength(count);
  const stay = await createFolioStay(api.post, rooms, name, paid, false, hours);
  const path = "/api/reservations/" + stay.id;
  const folios = path + "/room-departures";
  const options = () => api.read<Options>(folios);
  const ids = (await options()).occupied.map(r => r.id);
  const selection = {services: [], additionalFee: 0};
  const quote = (slotIds: number[]) => api.post(folios + "/quote", {slotIds, selection, billingMode: "SEPARATE"}).then(r => qaData<Quote>(r));
  const command = (total: number) => ({billingMode: "SEPARATE", selection, expectedTotal: total, cashReceived: 0});
  const tender = (slotIds: number[], q: Quote, provider: "SEPAY" | "CASH") =>
    ({slotIds, tender: {provider, selection, expectedTotal: q.total, expectedDue: q.cashDue}});
  async function cleanRooms() {
    for (const room of rooms) {
      expect(await api.read<QaRoom>("/api/rooms/" + room.id)).toMatchObject({status: "AVAILABLE", cleaningStatus: "DIRTY"});
      await qaData(await api.patch("/api/rooms/" + room.id + "/cleaning-status?cleaningStatus=CLEAN", null));
    }
  }
  async function closeAndClean() {
    const balance = await api.read<{outstandingAmount: number}>(path + "/checkout-reconciliation");
    if (balance.outstandingAmount) await qaData(await api.post("/api/payments/cash", {bookingId: stay.id, purpose: "FINAL_PAYMENT"}));
    await qaData(await api.patch("/api/reservations/check-out/" + stay.id, {}));
    const bill = await api.read<Invoice>(path + "/invoice");
    expect(bill.balanceAmount).toBe(0);
    expect(bill.totalAmount).toBe(bill.netPaidAmount);
    await cleanRooms();
    return bill;
  }
  return {api, stay, path, folios, options, ids, command, quote, tender, closeAndClean, cleanRooms};
}

// Legacy bookings cannot be created by today's booking form. Seed their historical terms on the
// synthetic stay only. All cashier, refund, checkout and invoice operations go through the API.
function legacyTerms(stay: number, hours: number, price: number, tax: number, discount: number, fee: number) {
  requireIsolation();
  const terms = {stay, hours, price, tax, discount, fee};
  for (const value of Object.values(terms)) expect(Number.isSafeInteger(value) && value >= 0).toBe(true);
  execFileSync("docker", ["exec", "-i", "hotel-isolated-qa-postgres-1", "psql", "-X", "-U", "hotel_qa", "-d", "hotel_qa", "-v", "ON_ERROR_STOP=1",
    ...Object.entries(terms).flatMap(([key, value]) => ["-v", key + "=" + value])], {input: `
    BEGIN;
    UPDATE reservation_room_types SET room_price=:price, subtotal=:price * quantity WHERE reservation_id=:stay;
    UPDATE reservations SET pricing_version='LEGACY_V1', check_in=clock.start_time, actual_check_in=clock.start_time,
        check_out=clock.start_time + :hours * interval '1 hour', discount_amount=:discount, tax_amount=:tax,
        checkout_additional_fee=:fee, early_checkout_adjustment=0, late_checkout_fee=0,
        total_amount=(SELECT SUM(subtotal) FROM reservation_room_types WHERE reservation_id=:stay) - :discount + :tax + :fee
    FROM (SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '15 minutes' AS start_time) clock
    WHERE id=:stay AND status='CHECKED_IN';
    COMMIT;`});
}

test("legacy discounted stay closes with a zero invoice and no unfunded refund", async ({request}) => {
  const d = await folioStay(request, "QA giá cũ giảm hết tiền phòng");
  legacyTerms(d.stay.id, 24, 330000, 0, 250000, 0);
  const ids = [d.ids[0]];
  const selection = {services: [], additionalFee: 0};
  const q = await qaData<Quote>(await d.api.post(d.folios + "/quote", {slotIds: ids, selection, billingMode: "GROUP"}));
  await qaData(await d.api.post(d.folios, {slotIds: ids, command: {...d.command(q.total), billingMode: "GROUP"}}));
  const bill = await d.closeAndClean();
  expect(bill.totalAmount).toBe(0);
  expect(bill.grossPaidAmount).toBe(0);
  expect(bill.completedRefundAmount).toBe(0);
});

test("first legacy folio uses the same tax-inclusive balance for quoting and collecting", async ({request}) => {
  const d = await folioStay(request, "QA phiếu đầu có thuế", 2, 2, 120000);
  legacyTerms(d.stay.id, 2, 110000, 20000, 0, 0);
  const selection = {services: [], additionalFee: 0, useCredit: 100000};
  const q = await qaData<Quote>(await d.api.post(d.folios + "/quote", {slotIds:d.ids, selection, billingMode:"SEPARATE"}));
  expect(q.cashDue).toBe(100000);
  const tender = {provider:"CASH", selection, expectedTotal:q.total, expectedDue:q.cashDue};
  await qaData(await d.api.post(d.folios + "/payments", {slotIds:d.ids,tender}));
  await qaData(await d.api.post(d.folios, {slotIds:d.ids, command:{...d.command(q.total),selection}}));
  const bill = await d.api.read<Invoice>(d.path + "/invoice");
  expect(bill.totalAmount).toBe(220000);
  expect(bill.balanceAmount).toBe(0);
  await d.cleanRooms();
});

for (const separate of [false, true]) test("refunded folio keeps its net allocation after a new shared fee, separate=" + separate, async ({request}) => {
  const d = await folioStay(request, "QA đối trừ sau hoàn", 1, 3);
  legacyTerms(d.stay.id, 1, 100000, 0, 250000, 100000);
  const a = [d.ids[0]], b = [d.ids[1]];
  const quoteA = await d.quote(a);
  await qaData(await d.api.post(d.folios + "/payments", d.tender(a, quoteA, "CASH")));
  await qaData(await d.api.post(d.folios, {slotIds:a,command:d.command(quoteA.total)}));
  const checkout = "/api/reservations/check-out/" + d.stay.id;
  const setFee = (fee: number) => d.api.patch(checkout + "/additional-fee", {additionalFee:fee, reasonCode:"QA_ADJUSTMENT", reason:"Điều chỉnh phụ phí chung trên QA"}).then(qaData);
  await setFee(0);
  await qaData(await d.api.patch(checkout + "/refund", {refundChannel:"CASH_AT_COUNTER"}));
  const refunds = await d.api.read<{bookingId: number; refundId: string; amount: number}[]>("/api/payments/refunds/pending");
  const refund = refunds.find(r => r.bookingId === d.stay.id)!;
  expect(refund.amount).toBe(50000);
  await qaData(await d.api.patch("/api/payments/refund/" + refund.refundId + "/cash-complete", {confirmed:true}));
  if (!separate) await qaData(await d.api.post(d.folios, {slotIds:b,command:{...d.command(100000),billingMode:"GROUP"}}));
  await setFee(100000);
  if (separate) {
    const quoteB = await d.quote(b);
    expect(quoteB.cashDue).toBe(100000);
    await qaData(await d.api.post(d.folios + "/payments", d.tender(b, quoteB, "CASH")));
    await qaData(await d.api.post(d.folios, {slotIds:b,command:d.command(quoteB.total)}));
  }
  await d.closeAndClean();
  const bill = await d.api.read<Invoice & {netSeparatelySettledAmount:number}>(d.path + "/invoice");
  expect(bill.totalAmount).toBe(150000);
  expect(bill.completedRefundAmount).toBe(50000);
  expect(bill.separatelySettledAmount).toBe(separate ? 200000 : 100000);
  expect(bill.netSeparatelySettledAmount).toBe(separate ? 150000 : 50000);
});

test("concurrent cash requests post once; closing a paid draft transfers its exact credit to the shared bill", async ({request}) => {
  const d = await folioStay(request, "QA thu tiền đồng thời");
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
    const d = await folioStay(request, "QA đối soát " + scenario);
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

test("on-time transfer delivered after expiry settles the open folio once at its displayed QR amount", async ({request}) => {
  test.setTimeout(140_000);
  const d = await folioStay(request, "QA thông báo chuyển khoản đến muộn");
  const ids = [d.ids[0]];
  const q = await d.quote(ids);
  const pay = await qaData<BankPayment>(await d.api.post(d.folios + "/payments", d.tender(ids, q, "SEPAY")));
  const transferDate = sePayTransactionDate();
  expect((await d.options()).collection?.pendingAmount).toBe(pay.expectedAmount);
  const changed = {...q, cashDue: q.cashDue + 20000, total: q.total + 20000};
  expect((await d.api.post(d.folios + "/payments", d.tender(ids, changed, "SEPAY"))).status()).toBe(400);
  await expect.poll(async () => (await d.api.read<{status: string}>("/api/payments/" + pay.transactionId)).status,
    {timeout: 110_000, intervals: [3000]}).toBe("CANCELLED");
  await settleThroughSignedWebhook(request, pay, 401, pay.expectedAmount, transferDate);
  expect((await d.quote(ids)).cashDue).toBe(0);
  const folio = await qaData<Folio>(await d.api.post(d.folios, {slotIds: ids, command: d.command(q.total)}));
  expect(folio.qrCollected).toBe(pay.expectedAmount);
  expect((await d.closeAndClean()).completedRefundAmount).toBe(0);
});

for (const newestFirst of [false, true]) test("two expired QRs settle the same room once, newest callback first=" + newestFirst, async ({request}) => {
  test.setTimeout(250_000);
  const d = await folioStay(request, "QA hai QR hết hạn");
  const ids = [d.ids[0]];
  const quote = await d.quote(ids);
  async function expiredQr() {
    const payment = await qaData<BankPayment>(await d.api.post(d.folios + "/payments", d.tender(ids, quote, "SEPAY")));
    const transferredAt = sePayTransactionDate();
    await expect.poll(async () => (await d.api.read<{status:string}>("/api/payments/" + payment.transactionId)).status,
      {timeout: 110_000, intervals: [3000]}).toBe("CANCELLED");
    return {payment, transferredAt};
  }
  const first = await expiredQr(), second = await expiredQr();
  expect(first.payment.transactionId).not.toBe(second.payment.transactionId);
  for (const event of newestFirst ? [second, first] : [first, second]) {
    await settleThroughSignedWebhook(request, event.payment, event === first ? 501 : 502, quote.cashDue, event.transferredAt);
  }
  const accepted = (p: BankPayment) => d.api.read<{acceptedAmount:number}>("/api/payments/" + p.transactionId);
  expect((await accepted(first.payment)).acceptedAmount).toBe(0);
  expect((await accepted(second.payment)).acceptedAmount).toBe(quote.cashDue);
  const pending = await d.api.read<{bookingId:number; refundId:string; refundCode:string; amount:number}[]>("/api/payments/refunds/pending");
  const rows = pending.filter(refund => refund.bookingId === d.stay.id);
  expect(rows).toHaveLength(1);
  const refund = rows[0];
  expect(refund.amount).toBe(quote.cashDue);
  await qaData(await d.api.put("/api/payments/refunds/" + refund.refundId + "/recipient",
    {bankCode:"TPB", bankName:"TPBank", accountNumber:"1234567890", accountHolderName:"QA HOTEL"}));
  await deliverSignedWebhook(request, {id:Date.now() + 504, gateway:"TPBank", transactionDate:sePayTransactionDate(),
    accountNumber:process.env.QA_MERCHANT_BANK_ACCOUNT, content:"HOAN " + refund.refundCode, transferType:"out",
    transferAmount:refund.amount, accumulated:0, referenceCode:"QA-FOLIO-OUT-" + randomUUID()});
  expect((await d.quote(ids)).cashDue).toBe(0);
  const receipt = await qaData<Folio>(await d.api.post(d.folios, {slotIds:ids, command:d.command(quote.total)}));
  expect(receipt.qrCollected).toBe(quote.cashDue);
  expect((await d.closeAndClean()).completedRefundAmount).toBe(quote.cashDue);
});

test("new per-guest services default to guests still in house after a room leaves", async ({request}) => {
  const d = await folioStay(request, "QA số khách dùng dịch vụ sau trả căn");
  const ids = [d.ids[0]];
  const q = await d.quote(ids);
  await qaData(await d.api.post(d.folios, {slotIds: ids, command: {...d.command(q.total), billingMode: "GROUP"}}));
  const stay = await d.api.read<{guestCount: number; inHouseGuestCount: number}>(d.path);
  expect(stay.guestCount).toBe(2);
  expect(stay.inHouseGuestCount).toBe(1);
  const catalog = await d.api.read<{id: number; pricingUnit: string}[]>("/api/add-on-services?flow=IN_STAY");
  const serviceId = catalog.find(service => service.pricingUnit === "PER_GUEST")!.id;
  expect((await d.api.post(d.path + "/services", {serviceId, quantity: 2})).status()).toBe(400);
  const order = await qaData<{id: number; quantity: number; unitPrice: number; totalPrice: number}>(await d.api.post(d.path + "/services", {serviceId}));
  expect(order.quantity).toBe(1);
  expect(order.totalPrice).toBe(order.unitPrice);
  for (const status of ["CONFIRMED", "FULFILLED"]) await qaData(await d.api.patch(d.path + "/services/" + order.id + "/status", {status}));
  const bill = await d.closeAndClean();
  expect(bill.netPaidAmount).toBe(bill.totalAmount);
});

test("per-cycle service can be split one billed unit at a time across a room folio and the shared invoice", async ({request}) => {
  const d = await folioStay(request, "QA dịch vụ tính theo gói", 50);
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
