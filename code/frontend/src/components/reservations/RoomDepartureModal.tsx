"use client";

import { useEffect, useState } from "react";
import ViewportModal from "@/components/UI/ViewportModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { apiClient, getApiErrorCode, getApiErrorMessage } from "@/lib/api";
import Link from "next/link";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";
import { RoomFolioDetails, type RoomFolio, type RoomServiceLine } from "./RoomFolioDetails";
import CheckoutTenderActions from "./CheckoutTenderActions";

type Units = { orderId: number; quantity: number };
type Mode = "GROUP" | "SEPARATE";
type Selection = { services: Units[]; additionalFee: number; useCredit: number | null };
type Collection = { id: number; pick: {slotIds: number[]; selection: Selection}; pendingTransactionId: string | null; cash: number; qr: number };
type Options = { occupied: { id: number; name: string }[]; services: RoomServiceLine[]; folios: RoomFolio[]; remainingFee: number; groupTax: number; groupDiscount: number; collection: Collection | null };
type Quote = { roomCharge: number; extraCharge: number; serviceCharge: number; additionalFee: number; total: number; availableCredit: number; minimumCredit: number; maximumCredit: number; cashDue: number; cashCollected: number; qrCollected: number };
type Props = { id: number; code: string; onClose: () => void; onSaved: () => void; onEditFee: () => void; feeEditorOpen: boolean };

const button = "min-h-10 rounded-lg border border-[#0f2a43]/20 px-4 py-2 text-sm font-bold hover:border-[#B8944F] focus-visible:outline-2 focus-visible:outline-offset-2 disabled:opacity-50";
const vnd = (n: number) => new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" }).format(n);

export default function RoomDepartureModal({ id, code, onClose, onSaved, onEditFee, feeEditorOpen }: Props) {
  const { localize: t } = useLanguage();
  const [options, setOptions] = useState<Options | null>(null);
  const [slotIds, setSlotIds] = useState<number[]>([]);
  const [services, setServices] = useState<Units[]>([]);
  const [additionalFee, setAdditionalFee] = useState(0);
  const [useCredit, setUseCredit] = useState<number | null>(null);
  const [mode, setMode] = useState<Mode>("GROUP");
  const [confirmRelease, setConfirmRelease] = useState(false);
  const [shiftRequired, setShiftRequired] = useState(false);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [folio, setFolio] = useState<RoomFolio | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const url = `/api/reservations/${id}/room-departures`;
  const lastRooms = !!options?.occupied.length && options.occupied.length === slotIds.length;
  const collection = options?.collection;
  const pendingQr = collection?.pendingTransactionId;
  const billingMode = lastRooms || collection ? "SEPARATE" : mode;

  useEffect(() => {
    if (feeEditorOpen) return;
    const controller = new AbortController();
    apiClient.get<{ data: Options }>(url, { signal: controller.signal }).then(({ data }) => {
      if (controller.signal.aborted) return;
      setOptions(data.data);
      const open = data.data.collection;
      if (open) {
        setMode("SEPARATE");
        setSlotIds(open.pick.slotIds);
        setServices(open.pick.selection.services);
        setAdditionalFee(open.pick.selection.additionalFee);
        setUseCredit(open.pick.selection.useCredit);
        return;
      }
      setSlotIds(current => {
        const available = current.filter(id => data.data.occupied.some(room => room.id === id));
        return available.length || !data.data.occupied.length ? available : [data.data.occupied[0].id];
      });
    }).catch(e => { if (!controller.signal.aborted) setError(getApiErrorMessage(e, t("Không tải được danh sách căn.", "Could not load rooms."))); });
    return () => controller.abort();
  }, [url, refresh, feeEditorOpen, t]);

  useEffect(() => {
    if (!slotIds.length || !options?.occupied.length || folio || feeEditorOpen) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => { void apiClient.post<{ data: Quote }>(`${url}/quote`, { slotIds, billingMode, selection: { services, additionalFee, useCredit } }, { signal: controller.signal }).then(({ data }) => {
      if (!controller.signal.aborted) setQuote(data.data);
    }).catch(e => { if (!controller.signal.aborted) setError(getApiErrorMessage(e, t("Không tính được chi phí.", "Could not price these rooms."))); }); }, 160);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [url, slotIds, billingMode, services, additionalFee, useCredit, options, folio, feeEditorOpen, t]);

  function resetQuote() {
    setQuote(null);
    setUseCredit(null);
    setError("");
  }

  function reload() {
    setQuote(null);
    setError("");
    setOptions(null);
    setRefresh(value => value + 1);
  }

  function openQr(transactionId: string) {
    sessionStorage.setItem("staff_room_folio_transaction", transactionId);
    window.location.assign("/booking/payment-result?transactionId=" + encodeURIComponent(transactionId));
  }

  async function collect(provider: "CASH" | "SEPAY") {
    if (!quote || busy || pendingQr || quote.cashDue <= 0) return;
    const tender = {provider, selection: {services, additionalFee, useCredit}, expectedTotal: quote.total, expectedDue: quote.cashDue};
    const scope = "room-payment:" + id + ":" + slotIds.join(",") + ":" + JSON.stringify(tender);
    setBusy(true);
    setError("");
    setShiftRequired(false);
    try {
      const {data} = await apiClient.post<{transactionId: string; status: string}>(url + "/payments", {slotIds, tender},
        {headers: {"Idempotency-Key": getOrCreateIdempotencyKey(scope)}});
      clearIdempotencyKey(scope);
      if (provider === "SEPAY" && data.status === "PENDING") {
        openQr(data.transactionId);
        return;
      }
      reload();
      onSaved();
    } catch (e) {
      setShiftRequired(getApiErrorCode(e) === 5090);
      setError(getApiErrorMessage(e, t("Chưa ghi nhận được khoản thu. Hãy kiểm tra lại trạng thái.", "Could not confirm payment. Check its status.")));
    } finally { setBusy(false); }
  }

  async function release() {
    if (!collection || busy) return;
    setBusy(true);
    try {
      await apiClient.post(url + "/payments/" + collection.id + "/release", {expectedReceived: collection.cash + collection.qr});
      setConfirmRelease(false);
      setUseCredit(null);
      reload();
      onSaved();
    } catch (e) {
      setError(getApiErrorMessage(e, t("Chưa đóng được phiếu thu.", "Could not close the collection.")));
    } finally { setBusy(false); }
  }

  async function submit() {
    if (!quote || busy || pendingQr || (billingMode === "SEPARATE" && quote.cashDue > 0)) return;
    const body = { billingMode, selection: { services, additionalFee, useCredit }, expectedTotal: quote.total, cashReceived: 0 };
    const scope = `room-departure:${id}:${slotIds.join(",")}:${JSON.stringify(body)}`;
    setBusy(true);
    setError("");
    try {
      const { data } = await apiClient.post<{ data: RoomFolio }>(url, { slotIds, command: body },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } });
      setFolio(data.data);
      clearIdempotencyKey(scope);
      onSaved();
    } catch (e) {
      setError(getApiErrorMessage(e, t("Chưa xác nhận được kết quả. Hãy tải lại hoặc thử lại.", "Could not confirm the result. Refresh or retry.")));
    } finally {
      setBusy(false);
    }
  }

  return <ViewportModal open={!feeEditorOpen} onClose={onClose} busy={busy} labelledBy="room-departure-title" panelClassName="max-w-2xl" backdropClassName="invoice-modal-backdrop bg-[#091E30]/68">
    <div className="invoice-print-root overflow-y-auto bg-white p-4 text-[#0f2a43] sm:p-6">
      <div className="flex items-start justify-between gap-4 border-b border-[#0F2A43]/10 pb-3">
        <div><h2 id="room-departure-title" className="text-xl font-bold">{folio ? t("Phiếu trả phòng", "Room folio") : t("Trả từng căn", "Individual room checkout")}</h2>
          <p className="mt-1 text-sm font-semibold text-[#596873]">{code}{!folio && options && <> · {options.occupied.length} {t("căn đang ở", "occupied rooms")}</>}</p></div>
        <button className={button + " invoice-no-print"} type="button" disabled={busy} onClick={onClose}>{t("Đóng", "Close")}</button>
      </div>
      {error && <div role="alert" className="mt-4 text-sm text-red-800">{error} <button type="button" className="underline" onClick={reload}>{t("Tải lại", "Refresh")}</button></div>}
      {error && shiftRequired && <Link href="/dashboard/work-schedules" className="mt-2 inline-block text-sm font-bold underline">{t("Check-in ca làm việc", "Check in to work shift")}</Link>}
      {folio ? <div className="mt-5 space-y-3">
        <RoomFolioDetails folio={folio}/>
        <button className={button + " invoice-no-print"} type="button" onClick={() => window.print()}>{t("In phiếu", "Print folio")}</button>
      </div> : <>
        {!options && !error && <p role="status" className="mt-4">{t("Đang tải danh sách căn…", "Loading rooms…")}</p>}
        {options && !options.occupied.length && <p className="mt-4">{t("Đoàn đã trả hết căn. Khoản chung còn lại được xử lý ở Đối soát & trả phòng.", "All rooms have departed. Settle any shared balance at group checkout.")}</p>}
        {options && options.occupied.length > 0 && <fieldset disabled={busy} className="mt-5 space-y-4">
          <fieldset disabled={!!collection}><legend className="mb-2 text-sm font-semibold">{t("Chọn căn trả cùng lượt", "Rooms checking out together")} · {slotIds.length}/{options.occupied.length}</legend>
            <div className="grid max-h-48 grid-cols-2 gap-2 overflow-y-auto sm:grid-cols-3">{options.occupied.map(r =>
              <label key={r.id} className={`flex min-h-10 cursor-pointer items-center gap-2 rounded-lg border px-3 text-sm font-semibold ${slotIds.includes(r.id) ? "border-[#0F2A43] bg-[#EDF1F4]" : "border-[#D8DDE1]"}`}>
                <input type="checkbox" checked={slotIds.includes(r.id)} className="accent-[#0F2A43]" onChange={e => {
                  resetQuote(); setSlotIds(e.target.checked ? [...slotIds, r.id].sort((a,b) => a-b) : slotIds.filter(id => id !== r.id));
                }}/> {r.name}
              </label>)}</div></fieldset>
          {options.services.length > 0 && <fieldset disabled={!!collection}><legend className="mb-1 text-sm font-semibold">{t("Dịch vụ tính vào phiếu", "Services on this folio")}</legend>
            {options.services.map(s => <label key={s.id} className="flex items-center justify-between gap-3 border-b border-[#0F2A43]/10 py-2 text-sm">
              <span>{s.name}<small className="block text-[#596873]">{t("Còn trên đơn", "Unallocated")} · {s.quantity} · {vnd(s.amount)}</small></span>
              <input type="number" min={0} max={s.quantity} step={1} aria-label={t("Số lượng", "Quantity") + " " + s.name}
                className="min-h-10 w-16 rounded-lg border border-[#D8DDE1] px-2 text-center" value={services.find(v => v.orderId === s.id)?.quantity || 0}
                onChange={e => { const n = Number(e.target.value); if (!Number.isSafeInteger(n) || n < 0 || n > s.quantity) return;
                  resetQuote(); setServices(current => [...current.filter(v => v.orderId !== s.id), ...(n ? [{orderId: s.id, quantity: n}] : [])].sort((x, y) => x.orderId - y.orderId));
                }}/>
            </label>)}
          </fieldset>}
          {options.remainingFee > 0 && <label className="block text-sm font-semibold">{t("Phụ phí tính vào phiếu (đ)", "Fee on this folio (VND)")}
            <input type="number" min={0} max={options.remainingFee} step={1} inputMode="numeric" disabled={!!collection} value={additionalFee}
              className="mt-1 block min-h-10 w-full rounded-lg border border-[#D8DDE1] px-3"
              onChange={e => { const n = Number(e.target.value); if (Number.isSafeInteger(n) && n >= 0) { resetQuote(); setAdditionalFee(n); } }}/>
            <span className="mt-1 block text-xs font-normal text-[#596873]">{t("Phụ phí chưa phân bổ của đoàn", "Unallocated group fees")} · {vnd(options.remainingFee)}</span></label>}
          {!collection && <button type="button" className="min-h-9 rounded-lg border border-orange-300 bg-orange-50 px-3 text-xs font-bold text-orange-900" onClick={onEditFee}>{options.remainingFee ? t("Sửa phụ phí của đơn", "Edit booking fees") : t("Thêm phụ phí", "Add fee")}</button>}
          {(options.groupDiscount > 0 || options.groupTax > 0) && <p className="text-xs text-[#596873]">
            {t("Tính trên hóa đơn đoàn", "On the group invoice")} · {t("Thuế", "Tax")} {vnd(options.groupTax || 0)} · {t("Giảm giá", "Discount")} {vnd(options.groupDiscount || 0)}</p>}
          {lastRooms ? <p className="rounded-lg bg-[#F7F3E9] p-3 text-sm">{t("Đã chọn hết các căn còn ở. Lập một phiếu riêng tại đây, hoặc dùng Đối soát & trả phòng để gộp vào hóa đơn cuối.", "All remaining rooms are selected. Issue one separate folio here or use group checkout to settle the shared bill.")}</p> : <fieldset disabled={!!collection}><legend className="mb-2 text-sm font-semibold">{t("Cách thanh toán", "Settlement")}</legend>
            <div className="grid grid-cols-2 gap-2">{(["GROUP", "SEPARATE"] as const).map(value =>
              <label key={value} className={`flex min-h-12 cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-sm font-semibold ${mode === value ? "border-[#80632F] bg-[#F7F3E9]" : "border-[#D8DDE1] bg-white"}`}>
                <input type="radio" name="room-billing-mode" value={value} className="accent-[#0F2A43]" checked={mode === value}
                  onChange={() => { resetQuote(); setMode(value); }}/>
                {value === "GROUP" ? t("Gộp vào hóa đơn cuối", "Bill to group") : t("Thanh toán riêng", "Settle separately")}
              </label>)}</div>
          </fieldset>}
          {quote ? <>
            <section aria-label={t("Tóm tắt chi phí", "Charge summary")} className="rounded-xl border border-[#0F2A43]/12 bg-[#F6F5F0] p-4 text-sm tabular-nums">
              <h3 className="mb-3 font-bold">{t("Tóm tắt chi phí", "Charge summary")}</h3>
              <p className="flex justify-between gap-4"><span>{t("Tiền phòng", "Room charge")}</span><b>{vnd(quote.roomCharge)}</b></p>
              {quote.extraCharge > 0 && <p className="mt-2 flex justify-between gap-4"><span>{t("Phụ thu khách", "Extra guests")}</span><b>{vnd(quote.extraCharge)}</b></p>}
              {quote.serviceCharge > 0 && <p className="mt-2 flex justify-between gap-4"><span>{t("Dịch vụ đã chọn", "Selected services")}</span><b>{vnd(quote.serviceCharge)}</b></p>}
              <p className="mt-2 flex justify-between gap-4"><span>{t("Phụ phí của phiếu", "Allocated fee")}</span><b>{vnd(quote.additionalFee)}</b></p>
              <p className="mt-3 flex justify-between gap-4 border-t border-[#0F2A43]/15 pt-3 font-bold"><span>{t("Tổng phiếu", "Folio total")}</span><span>{vnd(quote.total)}</span></p>
            </section>
            {billingMode === "SEPARATE" && <section aria-label={t("Thanh toán phiếu", "Folio payment")} className={`rounded-xl border p-4 text-sm tabular-nums ${pendingQr ? "border-amber-300 bg-amber-50" : quote.cashDue > 0 ? "border-rose-200 bg-rose-50" : "border-emerald-200 bg-emerald-50"}`}>
              {quote.availableCredit > 0 && <p className="mb-2 flex justify-between gap-3"><span>{t("Cấn trừ từ tiền đã thu", "Applied from existing payments")}</span><span>{vnd(quote.availableCredit)}</span></p>}
              {quote.cashCollected > 0 && <p className="mb-2 flex justify-between gap-3"><span>{t("Đã thu tiền mặt", "Cash received")}</span><b>{vnd(quote.cashCollected)}</b></p>}
              {quote.qrCollected > 0 && <p className="mb-2 flex justify-between gap-3"><span>{t("Đã nhận qua SePay", "Received via SePay")}</span><b>{vnd(quote.qrCollected)}</b></p>}
              {!collection && quote.maximumCredit > quote.minimumCredit && <label className="mb-2 flex min-h-8 items-center gap-2 text-xs"><input type="checkbox" checked={useCredit !== null}
                onChange={e => { resetQuote(); setUseCredit(e.target.checked ? quote.minimumCredit : null); }}/>
                {t("Giữ lại tối đa tiền đã thu cho phần chung", "Reserve available credit for the shared bill")}</label>}
              <p className="flex justify-between gap-3 font-bold"><span>{pendingQr ? t("Chờ xác nhận QR", "Awaiting QR confirmation") : quote.cashDue > 0 ? t("Khách cần thanh toán thêm", "Amount to collect") : t("Đã thanh toán đủ", "Paid in full")}</span><span>{vnd(quote.cashDue)}</span></p>
              {pendingQr ? <div className="mt-3 flex flex-wrap gap-2">
                <button type="button" className={button + " bg-white"} onClick={() => openQr(pendingQr)}>{t("Mở lại QR", "Reopen QR")}</button>
                <button type="button" className={button} onClick={reload}>{t("Kiểm tra trạng thái", "Check status")}</button>
              </div> : quote.cashDue > 0 && <CheckoutTenderActions disabled={busy} onCash={() => void collect("CASH")} onQr={() => void collect("SEPAY")}/>}
            </section>}
          </> : !error && <p role="status">{slotIds.length ? t("Đang tính chi phí…", "Calculating charges…") : t("Chọn ít nhất một căn để xem chi phí.", "Select a room to see its charges.")}</p>}
          {collection && <div className="text-xs">
            {confirmRelease ? <div className="space-y-2 rounded-lg border border-amber-200 bg-amber-50 p-3">
              <p>{t("Đóng phiếu thu sẽ hủy QR đang chờ. Tiền đã nhận vẫn được ghi trên hóa đơn chung. Các căn tiếp tục lưu trú.", "Closing cancels any pending QR and keeps received funds on the shared bill. The rooms remain occupied.")}</p>
              <div className="flex gap-2"><button className={button} type="button" onClick={() => setConfirmRelease(false)}>{t("Quay lại", "Back")}</button><button className={button} type="button" onClick={release}>{t("Đồng ý đóng phiếu thu", "Confirm closing collection")}</button></div>
            </div> : <button type="button" className="min-h-9 underline" onClick={() => setConfirmRelease(true)}>{t("Đóng phiếu thu và chọn lại", "Close collection and reselect")}</button>}
          </div>}
          <button type="button" disabled={!quote || !!pendingQr || (billingMode === "SEPARATE" && quote.cashDue > 0)} className={button + " w-full bg-[#0f2a43] text-white sm:ml-auto sm:block sm:w-auto"} onClick={submit}>
            {t("Xác nhận trả căn", "Confirm room checkout")}
          </button>
        </fieldset>}
        {options && options.folios.length > 0 && <section className="mt-6 border-t pt-3"><h3 className="font-bold">{t("Phiếu đã lập", "Issued folios")}</h3>
          {options.folios.map(f => <button key={f.slotId} type="button" className="mt-1 block text-sm underline" onClick={() => setFolio(f)}>{f.roomName} · {f.number}</button>)}</section>}
      </>}
    </div>
  </ViewportModal>;
}
