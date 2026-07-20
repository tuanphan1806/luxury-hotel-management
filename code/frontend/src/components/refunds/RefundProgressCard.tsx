"use client";

import Image from "next/image";
import { useId } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";

export type CustomerRefund = {
  channel: "VNPAY_ORIGINAL" | "MANUAL_BANK_TRANSFER" | "CASH_AT_COUNTER";
  status: "AWAITING_CUSTOMER_INFO" | "READY_FOR_MANUAL_TRANSFER" | "REQUESTED" | "PROCESSING" | "SUCCEEDED" | "FAILED" | "MANUAL_REVIEW";
  amount: number;
  requestedAt?: string;
  completedAt?: string;
  transferredAt?: string;
  proofImageUrl?: string;
  completionMethod?: "SEPAY_WEBHOOK" | "MANUAL_FALLBACK" | "CASH_HANDOVER" | "PROVIDER_API" | "LEGACY";
};

interface RefundProgressCardProps {
  refunds?: CustomerRefund[];
}

const formatVND = (value?: number) => `${Number(value || 0).toLocaleString("vi-VN")} đ`;

export default function RefundProgressCard({ refunds = [] }: RefundProgressCardProps) {
  const { localeTag, localize } = useLanguage();
  const titleId = useId();
  if (refunds.length === 0) return null;

  const statusMeta = (status: CustomerRefund["status"]) => {
    if (status === "SUCCEEDED") return {
      label: localize("Đã hoàn tiền", "Refund completed"),
      tone: "border-emerald-200 bg-emerald-50 text-emerald-800",
    };
    if (status === "FAILED" || status === "MANUAL_REVIEW") return {
      label: localize("Cần khách sạn kiểm tra", "Hotel review required"),
      tone: "border-rose-200 bg-rose-50 text-rose-800",
    };
    if (status === "AWAITING_CUSTOMER_INFO") return {
      label: localize("Chờ thông tin nhận tiền", "Awaiting payout details"),
      tone: "border-amber-200 bg-amber-50 text-amber-900",
    };
    return {
      label: localize("Đang xử lý", "In progress"),
      tone: "border-sky-200 bg-sky-50 text-sky-800",
    };
  };

  const formatTime = (value?: string) => value
    ? new Intl.DateTimeFormat(localeTag, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value))
    : "—";

  return (
    <section className="rounded-xl border border-[#0F2A43]/10 bg-white p-4 sm:p-5" aria-labelledby={titleId}>
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <p className="text-xs font-bold text-[#80632F]">{localize("Hoàn tiền", "Refund")}</p>
          <h4 id={titleId} className="mt-1 text-base font-bold text-[#0F2A43]">{localize("Tiến độ và đối soát", "Progress and reconciliation")}</h4>
        </div>
        <p className="text-xs text-[#66727C]">{localize("Thông tin do khách sạn xác nhận", "Confirmed by the hotel")}</p>
      </div>

      <div className="mt-4 space-y-3">
        {refunds.map((refund, index) => {
          const status = statusMeta(refund.status);
          const isBankTransfer = refund.channel === "MANUAL_BANK_TRANSFER";
          const completedTime = refund.transferredAt || refund.completedAt;
          return (
            <article key={`${refund.channel}-${refund.requestedAt || index}`} className="rounded-lg border border-[#0F2A43]/10 bg-[#F1F0EA] p-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p className="text-sm font-bold text-[#0F2A43]">
                    {isBankTransfer
                      ? localize("Hoàn qua QR", "QR refund")
                      : refund.channel === "CASH_AT_COUNTER"
                        ? localize("Tiền mặt tại quầy", "Cash at the front desk")
                        : localize("Phương thức thanh toán ban đầu", "Original payment method")}
                  </p>
                  <p className="mt-1 text-xs text-[#66727C]">
                    {refund.status === "SUCCEEDED"
                      ? `${localize("Hoàn lúc", "Completed at")} ${formatTime(completedTime)}`
                      : `${localize("Tạo yêu cầu lúc", "Requested at")} ${formatTime(refund.requestedAt)}`}
                  </p>
                </div>
                <div className="text-right">
                  <p className="font-bold tabular-nums text-[#0F2A43]">{formatVND(refund.amount)}</p>
                  <span className={`mt-1 inline-flex rounded-full border px-2.5 py-1 text-[11px] font-bold ${status.tone}`}>{status.label}</span>
                </div>
              </div>

              {isBankTransfer && refund.status === "SUCCEEDED" && refund.proofImageUrl && (
                <div className="mt-4 grid gap-3 border-t border-[#0F2A43]/10 pt-4 sm:grid-cols-[7rem_minmax(0,1fr)] sm:items-center">
                  <a href={refund.proofImageUrl} target="_blank" rel="noreferrer" className="block overflow-hidden rounded-lg border border-[#0F2A43]/10 bg-white focus:outline-none focus:ring-2 focus:ring-[#B8944F]">
                    <Image src={refund.proofImageUrl} alt={localize("Biên lai hoàn tiền của khách sạn", "Hotel refund receipt")} width={400} height={300} unoptimized className="aspect-[4/3] h-full w-full object-cover" />
                  </a>
                  <div>
                    <p className="text-sm font-bold text-[#0F2A43]">{localize("Biên lai chuyển khoản", "Transfer receipt")}</p>
                    <p className="mt-1 text-xs leading-5 text-[#66727C]">{localize("Nhấn vào ảnh để xem minh chứng đầy đủ do khách sạn cung cấp.", "Open the image to view the full proof supplied by the hotel.")}</p>
                    <a href={refund.proofImageUrl} target="_blank" rel="noreferrer" className="mt-2 inline-flex min-h-11 items-center text-sm font-bold text-[#0F2A43] underline decoration-[#B8944F] underline-offset-4">
                      {localize("Xem minh chứng", "View proof")}
                    </a>
                  </div>
                </div>
              )}

              {isBankTransfer && refund.status === "SUCCEEDED" && !refund.proofImageUrl && (
                <p className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs font-medium text-amber-900">
                  {refund.completionMethod === "SEPAY_WEBHOOK"
                    ? localize("Khoản hoàn đã được ngân hàng xác nhận tự động; kênh này không bắt buộc ảnh biên lai.", "This refund was confirmed automatically by the bank; a receipt image is not required.")
                    : localize("Khoản hoàn đã được ghi nhận hoàn tất trong sổ đối soát; ảnh biên lai không bắt buộc.", "This refund was recorded as completed in the reconciliation ledger; a receipt image is not required.")}
                </p>
              )}
              {refund.channel === "CASH_AT_COUNTER" && refund.status === "SUCCEEDED" && (
                <p className="mt-3 text-xs font-medium text-[#66727C]">{localize("Khách sạn đã xác nhận giao tiền mặt tại quầy; kênh này không yêu cầu ảnh biên lai.", "The hotel confirmed the cash handover at the front desk; no receipt image is required for this channel.")}</p>
              )}
            </article>
          );
        })}
      </div>
    </section>
  );
}
