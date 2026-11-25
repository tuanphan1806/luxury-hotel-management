"use client";

import { useLanguage } from "@/components/i18n/LanguageProvider";
import ViewportModal from "@/components/UI/ViewportModal";
import type { ReservationServiceItem } from "@/lib/add-on-services";
import { useState } from "react";
import { RoomFolioDetails, type RoomFolio } from "./RoomFolioDetails";

export interface ReservationInvoice {
  invoiceNumber: string;
  roomFolios?: RoomFolio[];
  groupRoomNames?: string[];
  separatelySettledAmount?: number;
  netSeparatelySettledAmount?: number;
  reservationId: number;
  reservationCode: string;
  issuedAt: string;
  issuedAtUtc?: string;
  hotelName: string;
  hotelAddress?: string;
  hotelPhone?: string;
  hotelEmail?: string;
  hotelTaxCode?: string;
  customerName: string;
  customerPhone?: string;
  customerEmail?: string;
  customerAddress?: string;
  plannedCheckIn: string;
  plannedCheckOut: string;
  actualCheckIn?: string;
  actualCheckOut?: string;
  guestCount: number;
  note?: string;
  pricingVersion?: "LEGACY_V1" | "MOTEL_PACKAGE_V2";
  roomTypes: Array<{
    roomTypeCode?: string;
    roomTypeName: string;
    quantity: number;
    pricePerRoomForStay: number;
    plannedRoomCharge?: number;
    actualRoomCharge?: number;
    plannedExtraGuestCharge?: number;
    extraGuestCharge?: number;
    plannedSubtotal: number;
    actualSubtotal?: number;
    appliedPackage?: "HOURLY" | "OVERNIGHT" | "DAILY";
    pricingSnapshotHash?: string;
  }>;
  payments: Array<{
    transactionId: string;
    transactionReference: string;
    provider: string;
    purpose?: string;
    status: string;
    amount: number;
    receivedAmount?: number | null;
    refundAmount?: number;
    refundProvider?: string;
    refundChannel?: string;
    paidAt?: string;
    paidAtUtc?: string;
    createdAt?: string;
  }>;
  services?: ReservationServiceItem[];
  plannedRoomCharge: number;
  roomCharge: number;
  actualRoomCharge?: number;
  extraGuestCharge?: number;
  postCommitmentRoomIncrease?: number;
  earlyCheckoutAdjustment: number;
  lateCheckoutFee: number;
  checkoutAdditionalFee: number;
  addOnServiceAmount?: number;
  discountAmount: number;
  taxAmount: number;
  totalAmount: number;
  grossPaidAmount: number;
  refundedAmount: number;
  completedRefundAmount?: number;
  netPaidAmount: number;
  balanceAmount: number;
  remainingAmount?: number;
  settlementStatus: "PAID" | "BALANCE_DUE" | "OVERPAID" | "REFUND_PENDING";
}

interface Props {
  invoice: ReservationInvoice;
  onClose: () => void;
}

export default function ReservationInvoiceModal({ invoice, onClose }: Props) {
  const { locale, localeTag, localize } = useLanguage();
  const [document, setDocument] = useState("ALL");
  const separate = (invoice.roomFolios || []).filter(f => f.billingMode === "SEPARATE");
  const selectedFolio = invoice.roomFolios?.find(f => String(f.slotId) === document);
  const issuedSeparately = invoice.separatelySettledAmount || 0;
  const netSeparate = invoice.netSeparatelySettledAmount ?? Math.min(issuedSeparately, invoice.totalAmount);
  const creditAfterIssue = issuedSeparately - netSeparate;
  const groupTotal = invoice.totalAmount - netSeparate;
  const money = (value?: number) =>
    Number(value || 0).toLocaleString(localeTag, {
      style: "currency",
      currency: "VND",
      maximumFractionDigits: 0,
    });
  const dateTime = (value?: string) => {
    if (!value) return "—";
    const date = new Date(value);
    return Number.isNaN(date.getTime())
      ? value
      : date.toLocaleString(localeTag, {
          day: "2-digit",
          month: "2-digit",
          year: "numeric",
          hour: "2-digit",
          minute: "2-digit",
        });
  };
  const purposeLabel = (value?: string) => {
    const labels: Record<string, { vi: string; en: string }> = {
      DEPOSIT: { vi: "Đặt cọc", en: "Deposit" },
      WALK_IN: { vi: "Thanh toán khách vãng lai", en: "Walk-in payment" },
      FINAL_PAYMENT: { vi: "Thanh toán còn lại", en: "Final payment" },
    };
    const label = labels[value || ""];
    return label ? label[locale] : value || localize("Thanh toán", "Payment");
  };
  const paymentProviderLabel = (value?: string) => {
    const labels: Record<string, { vi: string; en: string }> = {
      SEPAY: { vi: "SePay VietQR", en: "SePay VietQR" },
      CASH: { vi: "Tiền mặt", en: "Cash" },
    };
    const normalizedValue = value?.toUpperCase() || "";
    return labels[normalizedValue]?.[locale] || value || localize("Không xác định", "Unknown");
  };
  const refundLabel = (status: string) => {
    if (status === "REFUNDED") return localize("Đã hoàn", "Refunded");
    if (status === "REFUND_PENDING") return localize("Đang hoàn", "Refund in progress");
    return localize("Yêu cầu hoàn chưa thành công", "Refund request not completed");
  };
  const refundTone = (status: string) =>
    status === "REFUNDED"
      ? "text-emerald-700"
      : status === "REFUND_PENDING"
        ? "text-amber-700"
        : "text-rose-700";
  const refundChannelLabel = (channel?: string, provider?: string) => {
    const labels: Record<string, { vi: string; en: string }> = {
      MANUAL_BANK_TRANSFER: { vi: "Chuyển khoản ngân hàng", en: "Bank transfer" },
      CASH_AT_COUNTER: { vi: "Tiền mặt tại quầy", en: "Cash at front desk" },
      SEPAY: { vi: "SePay VietQR", en: "SePay VietQR" },
      CASH: { vi: "Tiền mặt", en: "Cash" },
    };
    const value = channel || provider;
    return value ? labels[value]?.[locale] || value : "";
  };
  const statusLabel = (value: string) => {
    const labels: Record<string, { vi: string; en: string }> = {
      SUCCESS: { vi: "Thành công", en: "Successful" },
      REFUNDED: { vi: "Đã hoàn", en: "Refunded" },
      REFUND_PENDING: { vi: "Chờ hoàn", en: "Refund pending" },
    };
    return labels[value]?.[locale] || value;
  };
  const packageLabel = (value?: string) => {
    const labels: Record<string, { vi: string; en: string }> = {
      HOURLY: { vi: "Nghỉ giờ", en: "Hourly" },
      OVERNIGHT: { vi: "Qua đêm", en: "Overnight" },
      DAILY: { vi: "Ngày đêm", en: "Daily" },
    };
    return value ? labels[value]?.[locale] || value : "";
  };
  const billableServices = (invoice.services || []).filter(
    (service) => service.status === "CONFIRMED" || service.status === "FULFILLED",
  );
  const settled = invoice.settlementStatus === "PAID" && invoice.balanceAmount === 0;
  const isPricingV2 = invoice.pricingVersion === "MOTEL_PACKAGE_V2";
  const showActualRoomLines = isPricingV2 || Boolean(invoice.roomFolios?.length);

  return (
    <ViewportModal
      open
      onClose={onClose}
      labelledBy="invoice-title"
      panelClassName="max-w-[210mm] print:max-h-none print:max-w-none print:overflow-visible print:rounded-none print:border-0 print:shadow-none"
      backdropClassName="invoice-modal-backdrop bg-[#091E30]/65"
      zIndexClassName="z-[90]"
    >
      <div className="invoice-print-root min-h-0 w-full overflow-y-auto bg-white text-[#0F2A43] print:overflow-visible">
        <div className="invoice-no-print sticky top-0 z-10 flex items-center justify-between gap-3 border-b border-[#0F2A43]/10 bg-white px-5 py-3">
          {invoice.roomFolios?.length ? <select aria-label={localize("Chứng từ cần in", "Document to print")} value={document} onChange={e => setDocument(e.target.value)} className="min-h-10 min-w-0 rounded-lg border px-2 text-sm font-bold">
            <option value="ALL">{localize("Tổng hợp cả đơn", "Full reservation invoice")}</option>
            {(groupTotal > 0 || creditAfterIssue > 0 || Boolean(invoice.groupRoomNames?.length)) && <option value="GROUP">{localize("Phần thanh toán chung", "Shared bill")}</option>}
            {invoice.roomFolios.map(f => <option key={f.slotId} value={f.slotId}>{localize("Phiếu căn", "Room folio")} {f.roomName}</option>)}
          </select> : <p className="text-sm font-bold text-[#0F2A43]">{localize("Xem trước hóa đơn", "Invoice preview")}</p>}
          <div className="flex gap-2">
            <button type="button" onClick={onClose} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-4 text-sm font-bold text-[#0F2A43]">{localize("Đóng", "Close")}</button>
            <button type="button" onClick={() => window.print()} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white hover:bg-[#091E30]">{localize("In hóa đơn", "Print invoice")}</button>
          </div>
        </div>

        {selectedFolio ? <article className="space-y-4 p-7 sm:p-10">
          <h2 id="invoice-title" className="text-xl font-bold">{invoice.hotelName} · {localize("Phiếu trả phòng", "Room folio")}</h2>
          <RoomFolioDetails folio={selectedFolio}/>
        </article> : document === "GROUP" ? <article className="space-y-4 p-7 sm:p-10">
          <header><h2 id="invoice-title" className="text-xl font-bold">{localize("Phần thanh toán chung", "Shared bill")}</h2>
            <p>{invoice.hotelName} · {invoice.invoiceNumber}</p>
            <p className="text-sm text-[#596873]">{dateTime(invoice.issuedAt)} · {invoice.groupRoomNames?.join(", ")}</p></header>
          <div className="space-y-2 text-sm tabular-nums">
            {[
              [localize("Tiền phòng", "Room charge"), (invoice.actualRoomCharge ?? invoice.roomCharge) + (isPricingV2 ? 0 : invoice.lateCheckoutFee || 0) - separate.reduce((n, f) => n + f.roomCharge, 0)],
              [localize("Phụ thu khách", "Extra guests"), (invoice.extraGuestCharge || 0) - separate.reduce((n, f) => n + f.extraCharge, 0)],
              ...billableServices.map(s => {
                const used = separate.flatMap(f => f.services).filter(x => x.id === s.id);
                return [s.serviceName + " × " + (s.billableQuantity - used.reduce((n, x) => n + x.quantity, 0)), s.totalPrice - used.reduce((n, x) => n + x.amount, 0)];
              }),
              [localize("Phụ phí khác", "Additional fee"), (invoice.checkoutAdditionalFee || 0) - separate.reduce((n, f) => n + f.additionalFee, 0)],
              [localize("Giảm giá", "Discount"), -(invoice.discountAmount || 0)],
              [localize("Thuế", "Tax"), invoice.taxAmount || 0],
            ].filter(([, amount]) => Number(amount) !== 0).map(([label, amount], i) =>
              <p key={i} className="flex justify-between gap-4"><span>{label}</span><span>{money(Number(amount))}</span></p>)}
            {creditAfterIssue > 0 && <p className="flex justify-between gap-4"><span>{localize("Phần giảm trừ áp dụng cho các phiếu đã lập", "Credit applied to previously issued folios")}</span><span>+ {money(creditAfterIssue)}</span></p>}
            <p className="flex justify-between border-t pt-2 text-base font-bold"><span>{localize("Tổng thanh toán chung", "Shared total")}</span><span>{money(groupTotal)}</span></p>
            <p className="flex justify-between"><span>{localize("Đã thanh toán", "Paid")}</span><span>{money(invoice.netPaidAmount - netSeparate)}</span></p>
          </div>
        </article> : <article className="p-7 sm:p-10">
          <header className="flex flex-col gap-6 border-b-2 border-[#0F2A43] pb-6 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <p className="text-xl font-extrabold tracking-tight text-[#0F2A43]">{invoice.hotelName || "Luxury Hotel"}</p>
              <p className="mt-1 text-sm text-[#66727C]">{localize("Hóa đơn thanh toán dịch vụ lưu trú", "Accommodation service invoice")}</p>
              {invoice.hotelAddress && <p className="mt-2 text-xs text-[#66727C]">{localize("Địa chỉ", "Address")}: {invoice.hotelAddress}</p>}
              {(invoice.hotelPhone || invoice.hotelEmail) && <p className="mt-1 text-xs text-[#66727C]">{localize("Liên hệ", "Contact")}: {[invoice.hotelPhone, invoice.hotelEmail].filter(Boolean).join(" · ")}</p>}
              {invoice.hotelTaxCode && <p className="mt-1 text-xs text-[#66727C]">{localize("Mã số thuế", "Tax code")}: {invoice.hotelTaxCode}</p>}
            </div>
            <div className="sm:text-right">
              <h1 id="invoice-title" className="text-2xl font-extrabold uppercase tracking-[0.08em] text-[#0F2A43]">{localize("Hóa đơn", "Invoice")}</h1>
              <p className="mt-2 font-mono text-sm font-bold">{invoice.invoiceNumber}</p>
              <p className="mt-1 text-xs text-[#66727C]">{localize("Ngày xuất", "Issued at")}: {dateTime(invoice.issuedAt)}</p>
            </div>
          </header>

          <section className="grid gap-6 border-b border-[#0F2A43]/12 py-6 sm:grid-cols-2">
            <div>
              <h2 className="text-xs font-extrabold uppercase tracking-[0.12em] text-[#0F2A43]">{localize("Khách thanh toán", "Billing customer")}</h2>
              <p className="mt-3 text-base font-bold">{invoice.customerName}</p>
              {invoice.customerPhone && <p className="mt-1 text-sm text-[#66727C]">{localize("Điện thoại", "Phone")}: {invoice.customerPhone}</p>}
              {invoice.customerEmail && <p className="mt-1 text-sm text-[#66727C]">Email: {invoice.customerEmail}</p>}
              {invoice.customerAddress && <p className="mt-1 text-sm text-[#66727C]">{localize("Địa chỉ", "Address")}: {invoice.customerAddress}</p>}
            </div>
            <div className="sm:text-right">
              <h2 className="text-xs font-extrabold uppercase tracking-[0.12em] text-[#0F2A43]">{localize("Thông tin đặt phòng", "Reservation details")}</h2>
              <p className="mt-3 text-sm"><span className="text-[#6B7280]">{localize("Mã đặt phòng", "Reservation code")}:</span> <strong>{invoice.reservationCode}</strong></p>
              <p className="mt-1 text-sm"><span className="text-[#6B7280]">{localize("Số khách theo đơn", "Guests")}:</span> <strong>{invoice.guestCount}</strong></p>
              <p className="mt-1 text-sm"><span className="text-[#6B7280]">{localize("Dự kiến", "Planned")}:</span> {dateTime(invoice.plannedCheckIn)} – {dateTime(invoice.plannedCheckOut)}</p>
              <p className="mt-1 text-sm"><span className="text-[#6B7280]">{localize("Thực tế", "Actual")}:</span> {dateTime(invoice.actualCheckIn)} – {dateTime(invoice.actualCheckOut)}</p>
            </div>
          </section>

          <section className="py-6">
            <h2 className="mb-3 text-xs font-extrabold uppercase tracking-[0.12em] text-[#0F2A43]">{localize("Chi tiết phòng", "Room details")}</h2>
            <div className="overflow-hidden rounded-lg border border-[#0F2A43]/15">
              <table className="w-full border-collapse text-sm">
                <thead className="bg-[#E5E9ED] text-left text-xs text-[#66727C]">
                  <tr><th className="px-4 py-3">{localize("Loại phòng", "Room type")}</th><th className="px-4 py-3 text-center">{localize("Số lượng", "Quantity")}</th><th className="px-4 py-3 text-right">{showActualRoomLines ? localize("Tiền phòng bình quân / căn", "Average room charge") : localize("Giá 1 phòng / kỳ lưu trú", "Price per room / stay")}</th><th className="px-4 py-3 text-right">{showActualRoomLines ? localize("Thành tiền thực tế", "Actual line total") : localize("Tạm tính dự kiến", "Planned subtotal")}</th></tr>
                </thead>
                <tbody className="divide-y divide-[#0F2A43]/10">
                  {invoice.roomTypes.map((item) => (
                    <tr key={item.roomTypeCode || item.roomTypeName}>
                      <td className="px-4 py-3 font-semibold">
                        <span>{item.roomTypeName}</span>
                        {isPricingV2 && item.appliedPackage && (
                          <span className="mt-1 block text-[11px] font-bold uppercase tracking-[0.08em] text-[#80632F]">
                            {packageLabel(item.appliedPackage)}
                          </span>
                        )}
                        {isPricingV2 && (item.extraGuestCharge || 0) > 0 && (
                          <span className="mt-1 block text-[11px] font-semibold text-[#80632F]">
                            {localize("Gồm phụ thu khách", "Includes extra guests")}: {money(item.extraGuestCharge)}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-center tabular-nums">{item.quantity}</td>
                      <td className="px-4 py-3 text-right tabular-nums">{money(showActualRoomLines && item.actualRoomCharge != null && item.quantity > 0 ? item.actualRoomCharge / item.quantity : item.pricePerRoomForStay)}</td>
                      <td className="px-4 py-3 text-right font-semibold tabular-nums">{money(showActualRoomLines ? item.actualSubtotal ?? item.plannedSubtotal : item.plannedSubtotal)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          {billableServices.length > 0 && (
            <section className="border-t border-[#0F2A43]/12 py-6">
              <h2 className="mb-3 text-xs font-extrabold uppercase tracking-[0.12em] text-[#0F2A43]">
                {localize("Dịch vụ thêm", "Add-on services")}
              </h2>
              <div className="overflow-hidden rounded-lg border border-[#0F2A43]/15">
                <table className="w-full border-collapse text-sm">
                  <thead className="bg-[#F0EADF] text-left text-xs text-[#66727C]">
                    <tr>
                      <th className="px-4 py-3">{localize("Dịch vụ", "Service")}</th>
                      <th className="px-4 py-3 text-center">{localize("Số lượng tính tiền", "Billable quantity")}</th>
                      <th className="px-4 py-3 text-right">{localize("Đơn giá", "Unit price")}</th>
                      <th className="px-4 py-3 text-right">{localize("Thành tiền", "Subtotal")}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[#0F2A43]/10">
                    {billableServices.map((service) => (
                      <tr key={service.id}>
                        <td className="px-4 py-3">
                          <p className="font-semibold">
                            {localize(service.serviceName, service.serviceNameEn)}
                          </p>
                          {service.notes && <p className="mt-1 text-xs text-[#66727C]">{service.notes}</p>}
                        </td>
                        <td className="px-4 py-3 text-center tabular-nums">{service.billableQuantity}</td>
                        <td className="px-4 py-3 text-right tabular-nums">{money(service.unitPrice)}</td>
                        <td className="px-4 py-3 text-right font-semibold tabular-nums">{money(service.totalPrice)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}

          <section className="grid gap-6 border-t border-[#0F2A43]/12 py-6 sm:grid-cols-[1fr_320px]">
            <div>
              <h2 className="mb-3 text-xs font-extrabold uppercase tracking-[0.12em] text-[#0F2A43]">{localize("Thanh toán", "Payments")}</h2>
              <div className="space-y-3">
                {invoice.payments.length === 0 ? <p className="text-sm text-[#6B7280]">{localize("Không có giao dịch thanh toán hợp lệ.", "No valid payment transactions.")}</p> : invoice.payments.map((payment) => (
                  <div key={payment.transactionId} className="border-b border-[#0F2A43]/10 pb-3 text-sm last:border-0">
                    <div className="flex justify-between gap-3"><span className="font-semibold">{paymentProviderLabel(payment.provider)} · {purposeLabel(payment.purpose)}</span><span className="font-bold tabular-nums">{money(payment.receivedAmount ?? payment.amount)}</span></div>
                    <div className="mt-1 flex justify-between gap-3 text-xs text-[#6B7280]"><span>{payment.transactionReference} · {statusLabel(payment.status)}</span><span>{dateTime(payment.paidAt || payment.createdAt)}</span></div>
                    {(payment.refundAmount || 0) > 0 && <p className={`mt-1 text-xs font-bold ${refundTone(payment.status)}`}>{refundLabel(payment.status)}: {money(payment.refundAmount)}{refundChannelLabel(payment.refundChannel, payment.refundProvider) ? ` · ${localize("qua", "via")} ${refundChannelLabel(payment.refundChannel, payment.refundProvider)}` : ""}</p>}
                  </div>
                ))}
              </div>
            </div>

            <div className="space-y-2 text-sm tabular-nums">
              <div className="flex justify-between"><span>{localize("Tiền phòng dự kiến", "Planned room charge")}</span><span>{money(invoice.plannedRoomCharge)}</span></div>
              {(invoice.earlyCheckoutAdjustment || 0) > 0 && <div className="flex justify-between text-blue-700"><span>{localize("Giảm do trả sớm", "Early checkout adjustment")}</span><span>− {money(invoice.earlyCheckoutAdjustment)}</span></div>}
              <div className="flex justify-between font-semibold"><span>{localize("Tiền phòng thực tế", "Actual room charge")}</span><span>{money(invoice.actualRoomCharge ?? invoice.roomCharge)}</span></div>
              {isPricingV2 && (invoice.postCommitmentRoomIncrease || 0) > 0 && <div className="flex justify-between text-xs text-[#66727C]"><span>{localize("Trong đó: tăng tiền phòng sau cam kết", "Included: room-price increase after commitment")}</span><span>{money(invoice.postCommitmentRoomIncrease)}</span></div>}
              {!isPricingV2 && (invoice.lateCheckoutFee || 0) > 0 && <div className="flex justify-between"><span>{localize("Phụ phí trả muộn", "Late checkout fee")}</span><span>+ {money(invoice.lateCheckoutFee)}</span></div>}
              {(invoice.extraGuestCharge || 0) > 0 && <div className="flex justify-between font-semibold text-[#80632F]"><span>{localize("Phụ thu khách thêm", "Extra guest charge")}</span><span>+ {money(invoice.extraGuestCharge)}</span></div>}
              {(invoice.checkoutAdditionalFee || 0) > 0 && <div className="flex justify-between"><span>{localize("Phụ phí khác", "Additional fee")}</span><span>+ {money(invoice.checkoutAdditionalFee)}</span></div>}
              {(invoice.addOnServiceAmount || 0) > 0 && <div className="flex justify-between font-semibold text-[#80632F]"><span>{localize("Dịch vụ thêm", "Add-on services")}</span><span>+ {money(invoice.addOnServiceAmount)}</span></div>}
              {(invoice.discountAmount || 0) > 0 && <div className="flex justify-between"><span>{localize("Giảm giá", "Discount")}</span><span>− {money(invoice.discountAmount)}</span></div>}
              {(invoice.taxAmount || 0) > 0 && <div className="flex justify-between"><span>{localize("Thuế", "Tax")}</span><span>+ {money(invoice.taxAmount)}</span></div>}
              <div className="mt-3 flex justify-between border-t-2 border-[#0F2A43] pt-3 text-lg font-extrabold text-[#0F2A43]"><span>{localize("Tổng cộng", "Total")}</span><span>{money(invoice.totalAmount)}</span></div>
              <div className="flex justify-between"><span>{localize("Đã thu", "Gross paid")}</span><span>{money(invoice.grossPaidAmount)}</span></div>
              {Boolean(invoice.roomFolios?.length) && <div className="space-y-1 border-l-2 pl-2 text-sm">
                {separate.map(f => <div key={f.slotId}>{f.roomName} · {f.number} · {localize("Đã thanh toán riêng", "Settled separately")} · {money(f.allocatedPayment)}</div>)}
                {creditAfterIssue > 0 && <div>{localize("Giảm trừ sau khi lập phiếu", "Credit after folio issue")} · − {money(creditAfterIssue)}</div>}
                <div>{localize("Phần thanh toán chung", "Shared bill")} · {money(groupTotal)}</div>
              </div>}
              {(invoice.refundedAmount || 0) > 0 && <div className="flex justify-between text-rose-700"><span>{localize("Đã hoàn", "Refunded")}</span><span>− {money(invoice.refundedAmount)}</span></div>}
              <div className="flex justify-between font-bold"><span>{localize("Thanh toán ròng", "Net paid")}</span><span>{money(invoice.netPaidAmount)}</span></div>
            </div>
          </section>

          <footer className="border-t-2 border-[#0F2A43] pt-5 text-center">
            <p className={`inline-flex rounded-full px-4 py-2 text-sm font-extrabold ${settled ? "bg-emerald-50 text-emerald-700" : "bg-amber-50 text-amber-800"}`}>{settled ? localize("Đã thanh toán đủ", "Paid in full") : `${localize("Cần kiểm tra đối soát", "Reconciliation required")}: ${money(invoice.balanceAmount)}`}</p>
            <p className="mt-4 text-xs text-[#6B7280]">{localize(`Cảm ơn quý khách đã sử dụng dịch vụ tại ${invoice.hotelName || "Luxury Hotel"}.`, `Thank you for staying at ${invoice.hotelName || "Luxury Hotel"}.`)}</p>
          </footer>
        </article>}
      </div>
    </ViewportModal>
  );
}
