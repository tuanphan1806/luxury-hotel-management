import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { requireIsolation } from "../qa/require-isolation";
import { openStaffShift } from "../qa/staff-shift";
import { settleThroughSignedWebhook } from "../qa/sepay-fixture";
import { createFolioStay, qaData as json, type QaRoom as Room, type QaStay as Stay } from "../qa/room-folio-stay";

type Slots = { occupied: { id: number; name: string }[]; services: { id: number; quantity: number; amount: number }[]; remainingFee: number; folios: Folio[] };
type Folio = { slotId: number; billingMode: string; roomCharge: number; extraCharge: number; serviceCharge: number; additionalFee: number; cashCollected: number; qrCollected: number; allocatedPayment: number; number: string };
type QaPayment = { transactionId: string; transactionReference: string; transferContent: string; expectedAmount: number; status: string };
type Quote = { total: number; cashDue: number; availableCredit: number; serviceCharge: number };
type Invoice = { totalAmount: number; netPaidAmount: number; actualRoomCharge: number; extraGuestCharge: number; checkoutAdditionalFee: number; addOnServiceAmount: number; balanceAmount: number; separatelySettledAmount: number; roomFolios: Folio[] };

test("three-room folio, shared bill, housekeeping and cashier reconciliation", async ({ page, request }, info) => {
  requireIsolation();
  test.setTimeout(200_000);
  const api = process.env.E2E_QA_API!;
  const capture = (name: string) => page.screenshot({
    path: info.outputPath(name),
    animations: "disabled",
    style: "nextjs-portal { display: none !important; }",
  });
  const desktop = { width: 1440, height: 1040 };
  await page.setViewportSize(desktop);
  await page.goto("/login");
  await expect(page.getByText("Đang kiểm tra phương thức đăng nhập...", { exact: true })).toBeHidden({ timeout: 30_000 });
  await page.locator("#login-email").fill("staff2");
  await page.locator("#login-password").fill("123456");
  const loggedIn = page.waitForResponse(r => r.request().method() === "POST" && r.url().includes("/auth/login"));
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  const staff = (await (await loggedIn).json()).accessToken as string;
  const { accessToken: admin } = await json<{ accessToken: string }>(await request.post(api + "/auth/login", { data: { username: "admin", password: "123456" } }));
  const closeShift = await openStaffShift(request, admin, staff);
  const headers = { Authorization: "Bearer " + staff };
  // Full-page navigation rotates this synthetic operator's device session. Reuse its current access token.
  page.on("request", req => {
    if (req.url().startsWith("http://localhost:13000/backend_proxy/api/")) {
      const current = req.headers().authorization;
      if (current?.startsWith("Bearer ")) headers.Authorization = current;
    }
  });
  page.on("response", async response => {
    if (response.url() !== "http://localhost:13000/backend_proxy/auth/refresh-token" || !response.ok()) return;
    const refreshed = await response.json();
    if (typeof refreshed.accessToken === "string") headers.Authorization = "Bearer " + refreshed.accessToken;
  });
  const post = (path: string, data: unknown, key: string = randomUUID()) =>
    request.post(api + path, { data, headers: { ...headers, "Idempotency-Key": key } });
  const patch = (path: string, data: unknown) =>
    request.patch(api + path, { data, headers: { ...headers, "Idempotency-Key": randomUUID() } });
  const get = <T>(path: string) => request.get(api + path, { headers }).then(r => json<T>(r));
  const rooms = await get<Room[]>("/api/rooms/search?status=AVAILABLE&cleaningStatus=CLEAN");
  const selected = rooms.filter(r => rooms.filter(o => o.roomTypeId === r.roomTypeId).length >= 3);
  const three = selected.filter(r => r.roomTypeId === selected[0]?.roomTypeId).slice(0, 3);
  expect(three).toHaveLength(3);
  const create = (chosen: Room[], name: string, paid: number, manual = false) => createFolioStay(post, chosen, name, paid, manual);
  const base = (stay: Stay) => "/api/reservations/" + stay.id;
  const setFee = (stay: Stay, fee: number) => patch("/api/reservations/check-out/" + stay.id + "/additional-fee", {
    additionalFee: fee, reasonCode: "QA_EXTRA_FEE", reason: "Kiểm thử phân bổ phụ phí của đoàn",
  });
  const payRemaining = async (stay: Stay) => {
    const before = await get<{ requiredAmount: number; acceptedAmount: number; outstandingAmount: number }>(base(stay) + "/checkout-reconciliation");
    expect(before.outstandingAmount).toEqual(before.requiredAmount - before.acceptedAmount);
    if (before.outstandingAmount > 0) {
      const payment = await json<{ acceptedAmount: number }>(await post("/api/payments/cash", { bookingId: stay.id, purpose: "FINAL_PAYMENT" }));
      expect(payment.acceptedAmount).toBe(before.outstandingAmount);
    }
    await json(await patch("/api/reservations/check-out/" + stay.id, {}));
    return get<Invoice>(base(stay) + "/invoice");
  };
  const clean = async (roomId: number) => { await json(await request.patch(api + "/api/rooms/" + roomId + "/cleaning-status", { headers, params: { cleaningStatus: "CLEAN" } })); };
  const unit = await create(three, "Đoàn Minh An", 20000);
  const path = base(unit);
  const departures = path + "/room-departures";
  await json(await setFee(unit, 45000));
  const catalog = await get<{ id: number; code: string }[]>("/api/add-on-services?flow=IN_STAY");
  const breakfast = catalog.find(s => s.code === "IN_ROOM_BREAKFAST")!;
  expect(breakfast).toBeDefined();
  const service = await json<{ id: number }>(await post(path + "/services", { serviceId: breakfast.id, quantity: 3 }));
  let options = await get<Slots>(departures);
  expect(options.occupied).toHaveLength(3);
  const first = options.occupied[0];
  const endpoint = departures + "/" + first.id;
  const selection = { services: [{ orderId: service.id, quantity: 1 }], additionalFee: 15000 };
  expect((await post(endpoint + "/quote", selection)).status()).toBe(400);
  for (const status of ["CONFIRMED", "FULFILLED"]) await json(await patch(path + "/services/" + service.id + "/status", { status }));
  const quote = await json<Quote>(await post(endpoint + "/quote", selection));
  expect(quote.serviceCharge).toBe(50000);
  expect(quote.availableCredit).toBe(20000);
  expect(quote.cashDue).toBe(quote.total - 20000);
  const reservedCredit = await json<Quote>(await post(endpoint + "/quote", {...selection, useCredit: 0}));
  expect(reservedCredit.cashDue).toBe(quote.total);
  expect((await post(endpoint + "/quote", {...selection, useCredit: 20001})).status()).toBe(400);
  expect((await post(endpoint + "/quote", { ...selection, additionalFee: 45001 })).status()).toBe(400);
  const command = { billingMode: "SEPARATE", selection, expectedTotal: quote.total, cashReceived: quote.cashDue };
  expect((await post(endpoint, { ...command, cashReceived: quote.cashDue - 1 })).status()).toBe(400);
  const pending = await json<{transactionId: string; status: string}>(await post("/api/payments/create", {
    bookingId: unit.id, purpose: "FINAL_PAYMENT", provider: "SEPAY",
  }));
  expect(pending.status).toBe("PENDING");
  const blocked = await post(departures + "/quote", {slotIds: [first.id], billingMode: "SEPARATE", selection});
  expect(blocked.status()).toBe(400);
  expect(await blocked.text()).toContain("QR");
  expect((await post(endpoint, command)).status()).toBe(400);
  expect((await post("/api/payments/result/" + pending.transactionId + "/abandon", {})).status()).toBe(204);

  await page.goto("/dashboard/reservations?reservationCode=" + unit.reservationCode);
  const individual = page.getByRole("button", { name: "Trả từng căn", exact: true });
  await expect(individual).toBeVisible({ timeout: 30_000 });
  await individual.scrollIntoViewIfNeeded();
  await capture("01-dashboard.png");
  await individual.click();
  const dialog = page.getByRole("dialog", { name: "Trả từng căn" });
  await expect(dialog.getByRole("checkbox", { name: first.name, exact: true })).toBeChecked();
  await dialog.getByRole("spinbutton", { name: /Số lượng Bữa sáng/ }).fill("1");
  await dialog.getByRole("spinbutton", { name: /Phụ phí tính vào phiếu/ }).fill("15000");
  await dialog.getByRole("radio", { name: "Thanh toán riêng", exact: true }).check();
  const cash = dialog.getByRole("button", { name: "Thu tiền mặt", exact: true });
  const qr = dialog.getByRole("button", { name: "Tạo QR", exact: true });
  await expect(cash).toBeVisible();
  await expect(qr).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Xác nhận trả căn" })).toBeDisabled();
  await capture("02-tach-hoa-don.png");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(dialog.getByRole("radio", { name: "Gộp vào hóa đơn cuối" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await cash.scrollIntoViewIfNeeded();
  await capture("03-dien-thoai.png");
  await page.setViewportSize(desktop);
  async function createQr() {
    const issuing = page.waitForResponse(r => r.request().method() === "POST" && r.url().endsWith(departures + "/payments"));
    await qr.click();
    const result = await issuing;
    expect(result.status()).toBe(200);
    await expect(page).toHaveURL(/booking\/payment-result/, {timeout: 30000});
    await expect(page.getByRole("button", {name: "Hủy QR và về phiếu phòng"})).toBeVisible({timeout: 30000});
    const transactionId = new URL(page.url()).searchParams.get("transactionId");
    expect(transactionId).toBeTruthy();
    const payment = await json<QaPayment>(await request.get(api + "/api/payments/result/" + transactionId));
    expect(payment.transactionId).toBe(transactionId);
    expect(payment.status).toBe("PENDING");
    expect(payment.expectedAmount).toBe(quote.cashDue);
    return payment;
  }
  // The QR page is shared with full settlement. A cancelled QR must leave the rooms occupied.
  const cancelled = await createQr();
  await page.getByRole("button", {name: "Hủy QR và về phiếu phòng"}).click();
  await expect(qr).toBeVisible({timeout: 30000});
  expect((await get<QaPayment>("/api/payments/" + cancelled.transactionId)).status).toBe("CANCELLED");
  const payment = await createQr();
  expect(payment.transactionId).not.toBe(cancelled.transactionId);
  await expect(page.getByRole("heading", {name: "Thanh toán phiếu phòng"})).toBeVisible();
  expect((await get<Slots>(departures)).occupied).toHaveLength(3);
  expect((await post("/api/payments/cash", {bookingId: unit.id, purpose: "FINAL_PAYMENT"})).status()).toBe(400);
  expect((await get<{roomFolioInProgress: boolean}>(path + "/checkout-reconciliation")).roomFolioInProgress).toBe(true);
  expect((await post(endpoint, command)).status()).toBe(400);
  expect((await post(departures + "/quote", {slotIds: [options.occupied[1].id], selection})).status()).toBe(400);
  await settleThroughSignedWebhook(request, payment, 101);
  await expect(dialog.getByText("Đã nhận qua SePay")).toBeVisible({timeout: 30000});
  await capture("08-qr-da-nhan.png");
  const sent = page.waitForResponse(r => r.request().method() === "POST" && r.url().endsWith(departures));
  await dialog.getByRole("button", { name: "Xác nhận trả căn" }).click();
  const saved = await sent;
  expect(saved.status(), await saved.text()).toBe(200);
  const folio = (await saved.json()).data as Folio;
  expect(folio.allocatedPayment).toBe(quote.total);
  expect(folio.cashCollected).toBe(0);
  expect(folio.qrCollected).toBe(quote.cashDue);
  await expect(page.getByRole("heading", {name: "Phiếu trả phòng"})).toBeVisible();
  await capture("04-phieu-can.png");
  const key = await saved.request().headerValue("idempotency-key");
  expect((await json<Folio>(await post(endpoint, {...command, cashReceived: 0}, key!))).number).toBe(folio.number);
  expect((await post(endpoint, command)).status()).toBe(400);
  expect((await setFee(unit, 14999)).status()).toBe(400);
  options = await get<Slots>(departures);
  expect(options.occupied).toHaveLength(2);
  expect(options.remainingFee).toBe(30000);
  expect(options.services.find(s => s.id === service.id)).toMatchObject({ quantity: 2, amount: 100000 });
  const roomA = three.find(room => room.roomName === first.name)!;
  expect(await get<Room>("/api/rooms/" + roomA.id)).toMatchObject({ status: "AVAILABLE", cleaningStatus: "DIRTY" });
  await clean(roomA.id);
  const newStay = await create([roomA], "Khách nhận lại căn", 0);
  await page.getByRole("button", {name: "Đóng", exact: true}).click();
  await page.goto("/dashboard/reservations?reservationCode=" + newStay.reservationCode);
  await expect(page.getByRole("button", {name: "Đối soát & trả phòng", exact: true})).toBeVisible();
  await expect(individual).toHaveCount(0);
  const single = await get<Slots>(base(newStay) + "/room-departures");
  expect((await post(base(newStay) + "/room-departures/" + single.occupied[0].id + "/quote", { services: [], additionalFee: 0 })).status()).toBe(400);

  const beforeFinal = await get<{requiredAmount: number; acceptedAmount: number; outstandingAmount: number}>(path + "/checkout-reconciliation");
  expect(beforeFinal.acceptedAmount).toBe(quote.total);
  const invoice = await payRemaining(unit);
  expect(invoice.roomFolios).toHaveLength(1);
  expect(invoice.separatelySettledAmount).toBe(folio.allocatedPayment);
  expect(invoice.addOnServiceAmount).toBe(150000);
  expect(invoice.checkoutAdditionalFee).toBe(45000);
  expect(invoice.totalAmount).toBe(invoice.actualRoomCharge + invoice.extraGuestCharge + 150000 + 45000);
  expect(invoice.totalAmount - invoice.separatelySettledAmount).toBe(beforeFinal.outstandingAmount);
  expect(invoice.balanceAmount).toBe(0);
  expect(invoice.netPaidAmount).toBe(invoice.totalAmount);
  await page.goto("/dashboard/reservations?reservationCode=" + unit.reservationCode);
  await page.getByRole("button", {name: "In hóa đơn", exact: true}).click();
  const docs = page.getByRole("combobox", {name: "Chứng từ cần in"});
  const fullInvoice = page.getByRole("dialog");
  const number = (amount: number) => amount.toLocaleString("vi-VN");
  const amountOnLine = (label: string) => fullInvoice.getByText(label, {exact: true}).locator("..");
  await expect(amountOnLine("Tổng cộng")).toContainText(number(invoice.totalAmount));
  await expect(amountOnLine("Thanh toán ròng")).toContainText(number(invoice.netPaidAmount));
  await page.setViewportSize({width: desktop.width, height: 1450});
  await capture("09-hoa-don-tong.png");
  await page.setViewportSize(desktop);
  await docs.selectOption("GROUP");
  await expect(page.getByRole("heading", {name: "Phần thanh toán chung"})).toBeVisible();
  await expect(amountOnLine("Tổng thanh toán chung")).toContainText(number(beforeFinal.outstandingAmount));
  await expect(amountOnLine("Phụ phí khác")).toContainText(number(30000));
  await expect(fullInvoice.getByText(/Bữa sáng.*× 2/)).toBeVisible();
  for (const room of three.filter(r => r.id !== roomA.id)) await expect(fullInvoice.locator("article header")).toContainText(room.roomName);
  await capture("05-hoa-don-hai-can.png");
  await docs.selectOption(String(first.id));
  await expect(page.getByText(folio.number, {exact: false}).last()).toBeVisible();
  await expect(amountOnLine("Tổng phiếu")).toContainText(number(quote.total));
  await expect(amountOnLine("Đã nhận qua SePay")).toContainText(number(quote.cashDue));
  await expect(amountOnLine("Cấn trừ từ tiền đã thu")).toContainText(number(20000));
  await page.emulateMedia({media: "print"});
  await expect(docs).toBeHidden();
  const printout = page.getByRole("article");
  await expect(printout).toHaveCount(1);
  await expect(printout).toBeVisible();
  await expect(printout).toContainText(folio.number);
  await page.emulateMedia({media: "screen"});
  await page.getByRole("dialog").getByRole("button", {name: "Đóng", exact: true}).click();
  expect(await get<Room>("/api/rooms/" + roomA.id)).toMatchObject({status: "CHECKED_IN", cleaningStatus: "CLEAN"});
  for (const room of three.filter(r => r.id !== roomA.id)) {
    expect(await get<Room>("/api/rooms/" + room.id)).toMatchObject({status: "AVAILABLE", cleaningStatus: "DIRTY"});
    await clean(room.id);
  }
  await payRemaining(newStay);
  await clean(roomA.id);

  const pair = await create(three.slice(0, 2), "Đoàn trả sau", 0);
  const pairPath = base(pair) + "/room-departures";
  const pairSlots = await get<Slots>(pairPath);
  const selection2 = {services: [], additionalFee: 0};
  const pairQuotes = await Promise.all(pairSlots.occupied.map(s => post(pairPath + "/" + s.id + "/quote", selection2).then(r => json<Quote>(r))));
  const attempts = await Promise.all(pairSlots.occupied.map((s, i) => post(pairPath + "/" + s.id, {billingMode: "GROUP", selection: selection2, expectedTotal: pairQuotes[i].total, cashReceived: 0})));
  expect(attempts.map(r => r.status()).sort()).toEqual([200, 400]);
  const groupFolio = await json<Folio>(attempts.find(r => r.ok())!);
  expect(groupFolio.allocatedPayment).toBe(0);
  const pairInvoice = await payRemaining(pair);
  expect(pairInvoice.separatelySettledAmount).toBe(0);
  expect(pairInvoice.balanceAmount).toBe(0);
  for (const room of three.slice(0, 2)) await clean(room.id);

  // Each departing guest gets their own paid folio, including the very last room.
  async function departSeparately(stay: Stay, slot: number) {
    const endpoint = base(stay) + "/room-departures/" + slot;
    const price = await json<Quote>(await post(endpoint + "/quote", selection2));
    if (price.cashDue > 0) {
      const paymentPath = base(stay) + "/room-departures/payments";
      const command = {slotIds: [slot], tender: {provider: "CASH", selection: selection2, expectedTotal: price.total, expectedDue: price.cashDue}};
      const key = randomUUID();
      const first = await json<QaPayment>(await post(paymentPath, command, key));
      expect((await json<QaPayment>(await post(paymentPath, command, key))).transactionId).toBe(first.transactionId);
      expect((await post(paymentPath, command)).status()).toBe(400);
    }
    const result = await json<Folio>(await post(endpoint, {billingMode: "SEPARATE", selection: selection2,
      expectedTotal: price.total, cashReceived: 0}));
    expect(result.cashCollected).toBe(price.cashDue);
    return result;
  }
  const allSeparate = await create(three, "Đoàn ba phiếu", 0);
  const threeSlots = await get<Slots>(base(allSeparate) + "/room-departures");
  for (const room of threeSlots.occupied) await departSeparately(allSeparate, room.id);
  expect((await get<Stay>(base(allSeparate))).status).toBe("CHECKED_OUT");
  const splitInvoice = await get<Invoice>(base(allSeparate) + "/invoice");
  expect(new Set(splitInvoice.roomFolios.map(f => f.number)).size).toBe(3);
  expect(splitInvoice.separatelySettledAmount).toBe(splitInvoice.totalAmount);
  expect(splitInvoice.balanceAmount).toBe(0);
  for (const room of three) await clean(room.id);

  const four = [...three, rooms.find(r => !three.some(other => r.id === other.id))!];
  const mixed = await create(four, "Đoàn bốn căn", 0);
  const mixedSlots = await get<Slots>(base(mixed) + "/room-departures");
  for (const room of mixedSlots.occupied.slice(0, 2)) await departSeparately(mixed, room.id);
  expect((await get<Slots>(base(mixed) + "/room-departures")).occupied).toHaveLength(2);
  const mixedInvoice = await payRemaining(mixed);
  expect(mixedInvoice.roomFolios).toHaveLength(2);
  expect(mixedInvoice.separatelySettledAmount).toBe(mixedInvoice.roomFolios.reduce((n, f) => n + f.allocatedPayment, 0));
  expect(mixedInvoice.totalAmount - mixedInvoice.separatelySettledAmount).toBeGreaterThan(0);
  expect(mixedInvoice.balanceAmount).toBe(0);
  for (const room of four) await clean(room.id);

  // A group fee deliberately left outside all individual folios remains collectible after physical departure.
  const sharedFee = await create(four.slice(0, 2), "Đoàn còn phí chung", 0);
  await json(await setFee(sharedFee, 10000));
  const feeSlots = await get<Slots>(base(sharedFee) + "/room-departures");
  for (const room of feeSlots.occupied) await departSeparately(sharedFee, room.id);
  expect((await get<Slots>(base(sharedFee) + "/room-departures")).occupied).toHaveLength(0);
  expect((await get<{outstandingAmount: number}>(base(sharedFee) + "/checkout-reconciliation")).outstandingAmount).toBe(10000);
  const feeInvoice = await payRemaining(sharedFee);
  expect(feeInvoice.totalAmount - feeInvoice.separatelySettledAmount).toBe(10000);
  expect(feeInvoice.balanceAmount).toBe(0);
  for (const room of four.slice(0, 2)) await clean(room.id);

  // One separate receipt can cover a chosen subset; unrelated rooms remain occupied.
  async function batch(stay: Stay, ids: number[], mode: "SEPARATE" | "GROUP", payByQr = false) {
    const endpoint = base(stay) + "/room-departures";
    const quote = await json<Quote>(await post(endpoint + "/quote", {slotIds: ids, billingMode: mode, selection: selection2}));
    if (payByQr) {
      const tender = {provider: "SEPAY", selection: selection2, expectedTotal: quote.total, expectedDue: quote.cashDue};
      const payment = await json<QaPayment>(await post(endpoint + "/payments", {slotIds: ids, tender}));
      expect(payment.expectedAmount).toBe(quote.cashDue);
      expect(quote.cashDue).toBeGreaterThan(0);
      await settleThroughSignedWebhook(request, payment, 102);
    }
    const command = {billingMode: mode, selection: selection2, expectedTotal: quote.total,
      cashReceived: mode === "GROUP" || payByQr ? 0 : quote.cashDue};
    const key = randomUUID();
    const receipt = await json<Folio & {rooms: {slotId: number}[]}>(await post(endpoint, {slotIds: ids, command}, key));
    expect(receipt.rooms.map(r => r.slotId).sort((a,b) => a-b)).toEqual([...ids].sort((a,b) => a-b));
    const replay = await json<Folio>(await post(endpoint, {slotIds: [...ids].reverse(), command}, key));
    expect(replay.number).toBe(receipt.number);
    expect(receipt.allocatedPayment).toBe(mode === "GROUP" ? 0 : quote.total);
    expect(receipt.qrCollected).toBe(payByQr ? quote.cashDue : 0);
    return receipt;
  }
  for (const parts of [[2, 1, 2], [2, 2, 2], [2, 3, 2], [1, 3, 2, 2], [3, 1, 5, 3], [6, 5, 4, 3]]) {
    const size = parts.reduce((a, b) => a + b, 0);
    const chosen = rooms.slice(0, size);
    expect(chosen).toHaveLength(size);
    const group = await create(chosen, "Đoàn QA " + size + " căn", 0, size === 5);
    const options = await get<Slots>(base(group) + "/room-departures");
    const originalIds = options.occupied.map(s => s.id);
    expect((await post(base(group) + "/room-departures/quote", {slotIds: [originalIds[0], originalIds[0]], selection: selection2})).status()).toBe(400);
    let remainingIds = [...originalIds];
    const receipts: Folio[] = [];
    if (size === 7) {
      await page.goto("/dashboard/reservations?reservationCode=" + group.reservationCode);
      await page.getByRole("button", {name: "Trả từng căn", exact: true}).click();
      const panel = page.getByRole("dialog");
      const firstPair = [options.occupied[0], options.occupied[5]];
      await panel.getByRole("checkbox", {name: firstPair[1].name, exact: true}).check();
      await panel.getByRole("radio", {name: "Thanh toán riêng", exact: true}).check();
      await panel.getByRole("button", {name: "Thêm phụ phí", exact: true}).click();
      const feeEditor = page.getByTestId("refund-fee-modal");
      await feeEditor.getByRole("textbox", {name: /Tổng phụ phí áp dụng/}).fill("70000");
      await feeEditor.getByRole("textbox", {name: /Ghi chú điều chỉnh/}).fill("Phí phát sinh của đoàn QA");
      await feeEditor.getByRole("button", {name: "Lưu và đối soát lại"}).click();
      await panel.getByRole("spinbutton", {name: /Phụ phí tính vào phiếu/}).fill("10000");
      const received = panel.getByRole("button", {name: "Thu tiền mặt", exact: true});
      await expect(received).toBeVisible();
      await capture("06-chon-nhom-bay-can.png");
      await page.setViewportSize({width: 390, height: 844});
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      await capture("07-bay-can-dien-thoai.png");
      await page.setViewportSize(desktop);
      await received.click();
      await expect(panel.getByText("Đã thu tiền mặt")).toBeVisible();
      const committed = page.waitForResponse(r => r.request().method() === "POST" && r.url().endsWith(base(group) + "/room-departures"));
      await panel.getByRole("button", {name: "Xác nhận trả căn"}).click();
      const result = await committed;
      expect(result.status(), await result.text()).toBe(200);
      const receipt = (await result.json()).data as Folio & {rooms: {slotId: number}[]};
      expect(receipt.cashCollected).toBe(receipt.roomCharge + 10000);
      expect(receipt.qrCollected).toBe(0);
      expect(receipt.rooms.map(r => r.slotId)).toEqual(firstPair.map(r => r.id));
      receipts.push(receipt);
      remainingIds = remainingIds.filter(id => !firstPair.some(r => r.id === id));
      await expect(panel.getByRole("heading", {name: "Phiếu trả phòng"})).toBeVisible();
      await panel.getByRole("button", {name: "Đóng", exact: true}).click();
    }
    for (const length of parts.slice(receipts.length, -1)) {
      // On the twelve-room stay one guest leaves early, but the group pays for that room at the end.
      const mode = size === 12 && length === 1 ? "GROUP" : "SEPARATE";
      receipts.push(await batch(group, remainingIds.splice(0, length), mode, size === 6 && receipts.length === 0));
    }
    expect((await get<Slots>(base(group) + "/room-departures")).occupied).toHaveLength(parts.at(-1)!);
    const groupInvoice = await payRemaining(group);
    expect(groupInvoice.roomFolios).toHaveLength(parts.length - 1);
    expect(groupInvoice.separatelySettledAmount).toBe(receipts.reduce((sum, r) => sum + r.allocatedPayment, 0));
    expect(groupInvoice.balanceAmount).toBe(0);
    expect(groupInvoice.netPaidAmount).toBe(groupInvoice.totalAmount);
    for (const room of chosen) await clean(room.id);
  }
  await closeShift(headers.Authorization.slice("Bearer ".length));
});
