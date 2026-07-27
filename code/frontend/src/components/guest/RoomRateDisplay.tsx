"use client";

import { useLanguage } from "@/components/i18n/LanguageProvider";

export interface PublicRoomRate {
  price?: number | string;
  packagePricingEnabled?: boolean;
  pricingAvailable?: boolean;
  includedGuests?: number;
  firstBlockMinutes?: number;
  firstBlockPrice?: number;
  extraUnitMinutes?: number;
  extraUnitPrice?: number;
  overnightPrice?: number;
  dailyPrice?: number;
  extraGuestPrice?: number;
}

interface RoomRateCompactProps {
  rate: PublicRoomRate;
  className?: string;
}

interface RoomRatePanelProps {
  rate: PublicRoomRate;
  className?: string;
}

interface StayPriceEstimateProps {
  estimatedPricePerRoom?: number;
  estimatedPackage?: "HOURLY" | "OVERNIGHT" | "DAILY";
  totalHours?: number;
  firstBlockMinutes?: number;
  firstBlockPrice?: number;
  fallbackPrice?: number;
  className?: string;
}

const asMoney = (value?: number | string) => {
  const amount = Number(value);
  return Number.isFinite(amount) ? amount : undefined;
};

const formatVND = (value?: number | string) => {
  const amount = asMoney(value);
  return amount == null
    ? "—"
    : amount.toLocaleString("vi-VN", {
        style: "currency",
        currency: "VND",
        maximumFractionDigits: 0,
      });
};

const durationLabel = (
  minutes: number | undefined,
  localize: (vi: string, en?: string) => string,
) => {
  const safeMinutes = Number(minutes || 0);
  if (safeMinutes <= 0) {
    return localize("giá tham khảo", "reference price");
  }
  if (safeMinutes > 0 && safeMinutes % 60 === 0) {
    const hours = safeMinutes / 60;
    return localize(`${hours} giờ đầu`, `first ${hours} hours`);
  }
  return localize(`${safeMinutes} phút đầu`, `first ${safeMinutes} minutes`);
};

export const comparablePublicRoomPrice = (rate: PublicRoomRate) =>
  asMoney(rate.pricingAvailable ? rate.firstBlockPrice : rate.price) ?? 0;

export function RoomRateCompact({ rate, className = "" }: RoomRateCompactProps) {
  const { localize } = useLanguage();
  const packageRateReady = Boolean(
    rate.packagePricingEnabled
      && rate.pricingAvailable
      && asMoney(rate.firstBlockPrice) != null,
  );

  if (rate.packagePricingEnabled && !rate.pricingAvailable) {
    return (
      <div className={`rounded-xl border border-white/75 bg-[#FBFAF6]/96 px-3.5 py-2.5 text-[#0F2A43] shadow-[0_10px_28px_rgba(9,30,48,0.18)] backdrop-blur-sm ${className}`}>
        <p className="text-[9px] font-extrabold uppercase tracking-[0.16em] text-[#80632F]">
          {localize("Bảng giá", "Rates")}
        </p>
        <p className="mt-1 text-xs font-bold">{localize("Kiểm tra theo thời gian", "Check for your stay")}</p>
      </div>
    );
  }

  if (!packageRateReady) {
    return (
      <div className={`rounded-xl border border-white/75 bg-[#FBFAF6]/96 px-3.5 py-2.5 text-[#0F2A43] shadow-[0_10px_28px_rgba(9,30,48,0.18)] backdrop-blur-sm ${className}`}>
        <p className="text-[9px] font-extrabold uppercase tracking-[0.16em] text-[#80632F]">
          {localize("Giá tham khảo", "Reference price")}
        </p>
        <p className="mt-0.5 font-sans text-lg font-extrabold tabular-nums">{formatVND(rate.price)}</p>
      </div>
    );
  }

  return (
    <div className={`min-w-[12rem] rounded-xl border border-white/75 bg-[#FBFAF6]/96 px-3.5 py-2.5 text-[#0F2A43] shadow-[0_10px_28px_rgba(9,30,48,0.18)] backdrop-blur-sm ${className}`}>
      <p className="text-[9px] font-extrabold uppercase tracking-[0.16em] text-[#80632F]">
        {localize("Giá từ", "From")}
      </p>
      <div className="mt-0.5 flex items-baseline gap-1.5">
        <strong className="font-sans text-lg font-extrabold tabular-nums tracking-[-0.02em]">
          {formatVND(rate.firstBlockPrice)}
        </strong>
        <span className="text-[10px] font-bold text-[#66727C]">/ {durationLabel(rate.firstBlockMinutes, localize)}</span>
      </div>
      <div className="mt-1.5 flex flex-wrap gap-x-2 gap-y-0.5 border-t border-[#0F2A43]/10 pt-1.5 text-[9px] font-bold text-[#66727C]">
        <span>{localize("Qua đêm", "Overnight")} {formatVND(rate.overnightPrice)}</span>
        <span aria-hidden="true" className="text-[#B8944F]">•</span>
        <span>24h {formatVND(rate.dailyPrice)}</span>
      </div>
    </div>
  );
}

export function RoomRatePanel({ rate, className = "" }: RoomRatePanelProps) {
  const { localize } = useLanguage();
  const packageRateReady = Boolean(
    rate.packagePricingEnabled
      && rate.pricingAvailable
      && asMoney(rate.firstBlockPrice) != null,
  );

  if (!packageRateReady) {
    return (
      <section className={`rounded-2xl border border-[#0F2A43]/10 bg-[#F7F4EC] p-4 ${className}`}>
        <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#80632F]">
          {localize("Giá tham khảo", "Reference price")}
        </p>
        <p className="mt-2 font-sans text-2xl font-extrabold tabular-nums text-[#0F2A43]">{formatVND(rate.price)}</p>
        <p className="mt-2 text-xs font-medium leading-5 text-[#66727C]">
          {localize("Chọn thời gian nhận và trả phòng để hệ thống tính giá chính xác.", "Choose check-in and check-out times for an exact price.")}
        </p>
      </section>
    );
  }

  const rows = [
    {
      key: "hourly",
      label: localize("Theo giờ", "Hourly"),
      price: rate.firstBlockPrice,
      unit: durationLabel(rate.firstBlockMinutes, localize),
      note: localize(
        `Sau đó +${formatVND(rate.extraUnitPrice)} mỗi ${(rate.extraUnitMinutes || 60) / 60} giờ`,
        `Then +${formatVND(rate.extraUnitPrice)} every ${(rate.extraUnitMinutes || 60) / 60} hour`,
      ),
    },
    {
      key: "overnight",
      label: localize("Qua đêm", "Overnight"),
      price: rate.overnightPrice,
      unit: localize("mỗi kỳ", "per stay"),
      note: localize("Khung 20:00–08:00 · quyền lưu trú tối đa 12 giờ", "20:00–08:00 window · up to 12 hours"),
    },
    {
      key: "daily",
      label: localize("Ngày đêm", "Daily"),
      price: rate.dailyPrice,
      unit: "24 giờ",
      note: localize("Tính theo từng chu kỳ 24 giờ", "Charged in rolling 24-hour cycles"),
    },
  ];

  return (
    <section className={`overflow-hidden rounded-2xl border border-[#0F2A43]/12 bg-[#FBFAF6] ${className}`}>
      <div className="flex items-start justify-between gap-4 border-b border-[#0F2A43]/10 bg-[#F1F0EA] px-4 py-3.5">
        <div>
          <p className="text-[9px] font-extrabold uppercase tracking-[0.18em] text-[#80632F]">{localize("Bảng giá lưu trú", "Stay rates")}</p>
          <h4 className="mt-1 font-serif text-lg font-bold text-[#0F2A43]">{localize("Chọn gói theo thời gian thực tế", "The right rate for your actual stay")}</h4>
        </div>
        <span className="shrink-0 rounded-full border border-[#B8944F]/35 bg-white px-2.5 py-1 text-[9px] font-extrabold uppercase tracking-[0.12em] text-[#80632F]">
          {localize("Giá niêm yết", "Published")}
        </span>
      </div>

      <div className="divide-y divide-[#0F2A43]/9">
        {rows.map((row) => (
          <div key={row.key} className="grid grid-cols-[minmax(0,1fr)_auto] gap-4 px-4 py-3.5 transition-colors hover:bg-[#F7F4EC]">
            <div>
              <p className="text-xs font-extrabold uppercase tracking-[0.12em] text-[#0F2A43]">{row.label}</p>
              <p className="mt-1 text-[11px] font-medium leading-5 text-[#66727C]">{row.note}</p>
            </div>
            <div className="text-right">
              <p className="font-sans text-lg font-extrabold tabular-nums tracking-[-0.02em] text-[#80632F]">{formatVND(row.price)}</p>
              <p className="mt-0.5 text-[10px] font-bold text-[#66727C]">/ {row.unit}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="border-t border-[#0F2A43]/10 bg-[#E8EFEA] px-4 py-3 text-xs font-semibold leading-5 text-[#315746]">
        {localize(
          `Khách thêm: ${formatVND(rate.extraGuestPrice)}/người/chu kỳ. Giá chính xác được tính sau khi chọn thời gian, số phòng và phân bổ khách.`,
          `Extra guest: ${formatVND(rate.extraGuestPrice)}/person/rate cycle. The exact price is calculated after dates, rooms and guests are selected.`,
        )}
      </div>
    </section>
  );
}

export function StayPriceEstimate({
  estimatedPricePerRoom,
  estimatedPackage,
  totalHours,
  firstBlockMinutes,
  firstBlockPrice,
  fallbackPrice,
  className = "",
}: StayPriceEstimateProps) {
  const { localize } = useLanguage();
  const hasEstimate = asMoney(estimatedPricePerRoom) != null;
  const packageLabel = estimatedPackage === "OVERNIGHT"
    ? localize("Qua đêm", "Overnight")
    : estimatedPackage === "DAILY"
      ? localize("Ngày đêm", "Daily")
      : localize("Theo giờ", "Hourly");

  return (
    <div className={`rounded-2xl border border-white/75 bg-[#FBFAF6]/96 px-4 py-3 text-right text-[#0F2A43] shadow-[0_12px_32px_rgba(9,30,48,0.2)] backdrop-blur-sm ${className}`}>
      <p className="text-[9px] font-extrabold uppercase tracking-[0.16em] text-[#80632F]">
        {hasEstimate ? localize("Tạm tính / phòng", "Estimate / room") : localize("Giá từ", "From")}
      </p>
      <strong className="mt-0.5 block font-sans text-xl font-extrabold tabular-nums tracking-[-0.02em]">
        {formatVND(estimatedPricePerRoom ?? firstBlockPrice ?? fallbackPrice)}
      </strong>
      <p className="mt-1 text-[10px] font-bold text-[#66727C]">
        {hasEstimate
          ? localize(`${packageLabel} · ${totalHours || 0} giờ`, `${packageLabel} · ${totalHours || 0} hours`)
          : durationLabel(firstBlockMinutes, localize)}
      </p>
    </div>
  );
}
