"use client";

import { useLanguage } from "@/components/i18n/LanguageProvider";

export type RoomServiceLine = { id: number; name: string; quantity: number; amount: number };
export type RoomFolio = {
  slotId: number; number: string; roomName: string; departedAt: string; billingMode: "GROUP" | "SEPARATE";
  roomCharge: number; extraCharge: number; serviceCharge: number; additionalFee: number;
  allocatedPayment: number; cashCollected: number; qrCollected?: number; services: RoomServiceLine[];
  rooms: {slotId: number; name: string; roomCharge: number; extraCharge: number}[];
  includedLateFee: number;
};

export function RoomFolioDetails({ folio }: { folio: RoomFolio }) {
  const { localize: t, localeTag } = useLanguage();
  const money = (value: number) => value.toLocaleString(localeTag, { style: "currency", currency: "VND" });
  const line = (label: string, amount: number) => <div className="flex justify-between gap-3" key={label}><span>{label}</span><span>{money(amount)}</span></div>;
  return <section className="space-y-4 text-sm">
    <header><h3 className="text-lg font-bold">{folio.roomName} · {folio.number}</h3>
      <p className="mt-1 text-[#596873]">{new Date(folio.departedAt).toLocaleString(localeTag)} · {folio.billingMode === "GROUP" ? t("Gộp vào hóa đơn đoàn", "Bill to group") : t("Đã thanh toán riêng", "Settled separately")}</p></header>
    <div className="space-y-2 tabular-nums">
      {line(t("Tiền phòng", "Room charge"), folio.roomCharge)}
      {folio.rooms.length > 1 && <div className="space-y-1 border-l-2 pl-3 text-xs text-[#596873]">{folio.rooms.map(r =>
        <div key={r.slotId} className="flex justify-between"><span>{r.name}</span><span>{money(r.roomCharge)}</span></div>)}</div>}
      {folio.includedLateFee > 0 && line(t("Trong đó phí trả muộn", "Includes late checkout"), folio.includedLateFee)}
      {folio.extraCharge > 0 && line(t("Phụ thu khách", "Extra guests"), folio.extraCharge)}
      {folio.services.map(s => <div key={s.id} className="flex justify-between gap-3"><span>{s.quantity} × {s.name}</span><span>{money(s.amount)}</span></div>)}
      {folio.additionalFee > 0 && line(t("Phụ phí của căn", "Allocated fee"), folio.additionalFee)}
      <div className="border-t border-[#0F2A43]/20 pt-2 text-base font-bold">{line(t("Tổng phiếu", "Folio total"), folio.roomCharge + folio.extraCharge + folio.serviceCharge + folio.additionalFee)}</div>
      {folio.billingMode === "SEPARATE" && <>
        {line(t("Cấn trừ từ tiền đã thu", "Applied from existing payments"), folio.allocatedPayment - folio.cashCollected - (folio.qrCollected || 0))}
        {folio.cashCollected > 0 && line(t("Đã thu tiền mặt", "Cash received"), folio.cashCollected)}
        {!!folio.qrCollected && line(t("Đã nhận qua SePay", "Received via SePay"), folio.qrCollected)}
      </>}
    </div>
  </section>;
}
