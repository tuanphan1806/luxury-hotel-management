"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { FACILITIES_CONTENT } from "@/constants/content";

export interface FacilityDetailItem {
  id?: number;
  facilityName?: string;
  facilityNameEn?: string;
  name?: string;
  description?: string;
  descriptionEn?: string;
  imageUrl?: string;
  imageUrls?: string[];
  icon?: string;
  image?: string;
  type?: string;
}

interface FacilityDetailModalProps {
  facility: FacilityDetailItem | null;
  onClose: () => void;
}

const focusableSelector = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])",
].join(",");

export default function FacilityDetailModal({ facility, onClose }: FacilityDetailModalProps) {
  const { localize } = useLanguage();
  const [portalRoot, setPortalRoot] = useState<HTMLElement | null>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const titleId = useId();

  useEffect(() => {
    setPortalRoot(document.body);
  }, []);

  useEffect(() => {
    if (!facility) return;

    const previousOverflow = document.body.style.overflow;
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    document.body.style.overflow = "hidden";

    const focusTimer = window.setTimeout(() => closeButtonRef.current?.focus(), 0);
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }

      if (event.key !== "Tab" || !dialogRef.current) return;
      const focusableElements = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(focusableSelector));
      if (!focusableElements.length) return;
      const firstElement = focusableElements[0];
      const lastElement = focusableElements[focusableElements.length - 1];

      if (event.shiftKey && document.activeElement === firstElement) {
        event.preventDefault();
        lastElement.focus();
      } else if (!event.shiftKey && document.activeElement === lastElement) {
        event.preventDefault();
        firstElement.focus();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => {
      window.clearTimeout(focusTimer);
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", handleKeyDown);
      previousFocusRef.current?.focus();
    };
  }, [facility, onClose]);

  if (!facility || !portalRoot) return null;

  const name = localize(facility.facilityName || facility.name, facility.facilityNameEn);
  const persistedImages = Array.from(
    new Set((facility.imageUrls || []).map((item) => item?.trim()).filter((item): item is string => Boolean(item))),
  ).slice(0, 2);
  const image = persistedImages[0] || facility.imageUrl || facility.icon || facility.image || FACILITIES_CONTENT.hero.bg;
  const detailImage = persistedImages[1];
  const isRoomFacility = facility.type?.toUpperCase() === "ROOM";

  return createPortal(
    <div
      className="ux-modal-backdrop fixed inset-0 z-[9999] grid place-items-center overflow-y-auto bg-[#091E30]/76 p-3 backdrop-blur-sm sm:p-6"
      data-testid="facility-detail-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        data-testid="facility-detail-modal"
        className="ux-modal-panel relative my-auto grid max-h-[calc(100dvh-1.5rem)] w-full max-w-4xl overflow-y-auto overscroll-contain rounded-[1.75rem] border border-white/20 bg-[#FBFAF6] shadow-[0_28px_90px_rgba(3,12,28,0.38)] sm:max-h-[calc(100dvh-3rem)] md:grid-cols-[0.9fr_1.1fr]"
      >
        <button
          ref={closeButtonRef}
          type="button"
          onClick={onClose}
          aria-label={localize("Đóng chi tiết tiện nghi", "Close facility details")}
          className="absolute right-4 top-4 z-20 flex h-11 w-11 items-center justify-center rounded-full border border-white/55 bg-[#FBFAF6]/94 text-xl font-bold text-[#0F2A43] shadow-md backdrop-blur transition hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2"
        >
          ×
        </button>

        <div className={`grid gap-2 bg-[#E5E9ED] p-2 ${detailImage ? "md:grid-rows-[minmax(20rem,1fr)_12rem]" : "md:min-h-[34rem]"}`}>
          <div className="relative min-h-64 overflow-hidden rounded-[1.25rem] bg-[#DCE2E7] sm:min-h-72 md:min-h-0">
            <ProgressiveImage
              src={image}
              fallbackSrc={FACILITIES_CONTENT.hero.bg}
              alt={name}
              fill
              sizes="(min-width: 768px) 45vw, 100vw"
              className="object-cover"
            />
          </div>
          {detailImage && (
            <figure className="relative min-h-40 overflow-hidden rounded-[1.25rem] bg-[#DCE2E7] md:min-h-0">
              <ProgressiveImage
                src={detailImage}
                fallbackSrc={image}
                alt={localize(`Chi tiết minh họa ${name}`, `Illustrative detail of ${name}`)}
                fill
                sizes="(min-width: 768px) 45vw, 100vw"
                className="object-cover"
              />
              <figcaption className="absolute bottom-3 left-3 rounded-full bg-[#091E30]/82 px-3 py-1.5 text-[9px] font-bold uppercase tracking-[0.16em] text-[#F5F1E8] backdrop-blur-sm">
                {localize("Hình ảnh minh họa", "Illustrative image")}
              </figcaption>
            </figure>
          )}
        </div>

        <div className="flex flex-col justify-center p-6 sm:p-8 md:p-10">
          <p className="pr-12 text-[10px] font-bold uppercase tracking-[0.22em] text-[#80632F]">
            {isRoomFacility ? localize("Tiện nghi trong phòng", "In-room facility") : localize("Không gian chung", "Shared facility")}
          </p>
          <h2 id={titleId} className="mt-3 font-serif text-3xl font-bold leading-tight text-[#0F2A43] sm:text-4xl">{name}</h2>
          <p className="mt-5 text-sm font-medium leading-7 text-[#66727C]">
            {localize(facility.description, facility.descriptionEn) || localize("Thông tin chi tiết đang được đội ngũ khách sạn cập nhật.", "Detailed information is being updated by the hotel team.")}
          </p>

          <dl className="mt-7 grid gap-3 sm:grid-cols-2">
            <div className="rounded-xl bg-[#EAE2D2] p-4">
              <dt className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Phạm vi", "Access")}</dt>
              <dd className="mt-2 text-sm font-bold text-[#0F2A43]">{isRoomFacility ? localize("Theo hạng phòng đã chọn", "Based on selected room type") : localize("Dành cho khách lưu trú", "For staying guests")}</dd>
            </div>
            <div className="rounded-xl bg-[#F1F0EA] p-4">
              <dt className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Xác nhận", "Confirmation")}</dt>
              <dd className="mt-2 text-sm font-bold text-[#0F2A43]">{localize("Kiểm tra cùng lễ tân khi cần", "Check with the front desk when needed")}</dd>
            </div>
          </dl>

          <p className="mt-6 border-l-2 border-[#B8944F] pl-4 text-xs font-medium leading-6 text-[#66727C]">
            {isRoomFacility
              ? localize("Tiện nghi thực tế phụ thuộc hạng phòng và cấu hình của phòng được gán khi nhận phòng.", "Actual amenities depend on the room type and the room assigned at check-in.")
              : localize("Thời gian phục vụ và điều kiện sử dụng có thể thay đổi; lễ tân sẽ xác nhận thông tin phù hợp với kỳ lưu trú của bạn.", "Opening times and access conditions may change; the front desk will confirm details for your stay.")}
          </p>

          <div className="mt-7 flex flex-wrap gap-3">
            <Link href="/rooms" onClick={onClose} className="inline-flex min-h-12 items-center justify-center rounded-xl bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#091E30]">
              {localize("Xem hạng phòng", "View room types")}
            </Link>
            <Link href="/contact" onClick={onClose} className="inline-flex min-h-12 items-center justify-center rounded-xl border border-[#0F2A43]/16 px-6 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#EAE2D2]">
              {localize("Hỏi lễ tân", "Ask the front desk")}
            </Link>
          </div>
        </div>
      </section>
    </div>,
    portalRoot,
  );
}
