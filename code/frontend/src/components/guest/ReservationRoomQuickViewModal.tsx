"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { GALLERY_HERO_IMAGES } from "@/constants/content";

export interface ReservationRoomQuickViewItem {
  id: number;
  typeName: string;
  typeNameEn?: string;
  description?: string;
  descriptionEn?: string;
  imageUrl?: string;
  gallery?: string[];
  price?: number;
  pricePerHour?: number;
  estimatedPricePerRoom?: number;
  totalHours?: number;
  maxGuestsPerRoom?: number;
  availableRooms?: number;
  facilities?: ReservationRoomFacilityItem[];
}

export interface ReservationRoomFacilityItem {
  id?: number;
  facilityName?: string;
  facilityNameEn?: string;
  imageUrl?: string;
  type?: string;
}

interface ReservationRoomQuickViewModalProps {
  room: ReservationRoomQuickViewItem | null;
  selectedQuantity: number;
  onQuantityChange: (quantity: number) => void;
  onClose: () => void;
}

const formatVND = (value?: number) =>
  typeof value === "number"
    ? value.toLocaleString("vi-VN", { style: "currency", currency: "VND", maximumFractionDigits: 0 })
    : "Liên hệ";

const focusableSelector = "a[href],button:not([disabled]),select:not([disabled]),[tabindex]:not([tabindex='-1'])";

export default function ReservationRoomQuickViewModal({
  room,
  selectedQuantity,
  onQuantityChange,
  onClose,
}: ReservationRoomQuickViewModalProps) {
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
    if (!room) return;

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
      const items = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(focusableSelector));
      if (!items.length) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => {
      window.clearTimeout(focusTimer);
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", handleKeyDown);
      previousFocusRef.current?.focus();
    };
  }, [room, onClose]);

  if (!room || !portalRoot) return null;

  const roomName = localize(room.typeName, room.typeNameEn);
  const maximumQuantity = Math.min(room.availableRooms ?? 10, 20);
  const facilities = room.facilities ?? [];
  const galleryImages = Array.from(new Set([
    ...(room.gallery ?? []),
    room.imageUrl,
    GALLERY_HERO_IMAGES.rooms,
  ].filter((image): image is string => Boolean(image)))).slice(0, 3);
  const estimatedPrice = room.estimatedPricePerRoom ?? room.price;
  const hourlyPrice = room.pricePerHour;

  return createPortal(
    <div
      data-testid="reservation-room-modal-backdrop"
      className="ux-modal-backdrop fixed inset-0 z-[9999] grid place-items-center overflow-y-auto bg-[#091E30]/76 p-3 backdrop-blur-sm sm:p-6"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        data-testid="reservation-room-modal"
        className="ux-modal-panel relative my-auto grid max-h-[calc(100dvh-1.5rem)] w-full max-w-6xl overflow-y-auto overscroll-contain rounded-[1.75rem] border border-white/20 bg-[#FBFAF6] shadow-[0_28px_90px_rgba(3,12,28,0.38)] sm:max-h-[calc(100dvh-3rem)] md:grid-cols-[0.92fr_1.08fr]"
      >
        <button
          ref={closeButtonRef}
          type="button"
          onClick={onClose}
          aria-label={localize("Đóng chi tiết phòng", "Close room details")}
          className="absolute right-4 top-4 z-20 flex h-11 w-11 items-center justify-center rounded-full border border-white/55 bg-[#FBFAF6]/94 text-xl font-bold text-[#0F2A43] shadow-md backdrop-blur transition hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2"
        >
          ×
        </button>

        <div className="grid min-h-[21rem] grid-cols-2 grid-rows-[14rem_7rem] gap-2 overflow-hidden bg-[#E5E9ED] p-2 md:min-h-[31rem] md:grid-cols-[1.45fr_0.85fr] md:grid-rows-2 md:p-3">
          {galleryImages.map((image, index) => {
            const caption = index === 0
              ? localize("Toàn cảnh", "Overview")
              : index === 1
                ? localize("Không gian chức năng", "Functional space")
                : localize("Chi tiết thiết kế", "Design detail");
            const featured = index === 0;
            return (
              <figure
                key={image}
                className={`group relative overflow-hidden rounded-[1.15rem] bg-[#DDE3E8] shadow-sm ${featured ? "col-span-2 md:col-span-1 md:row-span-2" : ""}`}
              >
                <ProgressiveImage
                  src={image}
                  fallbackSrc={room.imageUrl || GALLERY_HERO_IMAGES.rooms}
                  alt={`${roomName} — ${caption}`}
                  fill
                  sizes={featured ? "(min-width: 768px) 34vw, 100vw" : "(min-width: 768px) 18vw, 50vw"}
                  className="object-cover transition duration-500 ease-out group-hover:scale-[1.025]"
                  priority={featured}
                />
                <figcaption className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-[#071A2A]/82 via-[#071A2A]/28 to-transparent px-3 pb-2.5 pt-9 text-[10px] font-bold uppercase tracking-[0.12em] text-white sm:text-xs">
                  {caption}
                </figcaption>
              </figure>
            );
          })}
        </div>

        <div className="flex flex-col justify-center p-5 sm:p-6 lg:p-7">
          <p className="pr-12 text-[10px] font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Xem nhanh hạng phòng", "Room quick view")}</p>
          <h2 id={titleId} className="mt-2 font-serif text-3xl font-bold leading-tight text-[#0F2A43] sm:text-[2.15rem]">{roomName}</h2>
          <p className="mt-3 line-clamp-2 text-sm font-medium leading-6 text-[#66727C]">
            {localize(room.description, room.descriptionEn) || localize("Không gian nghỉ dưỡng yên tĩnh với đầy đủ tiện nghi cần thiết cho chuyến đi.", "A calm room with the essential facilities for your stay.")}
          </p>

          <dl className="mt-5 grid gap-2.5 sm:grid-cols-2 lg:grid-cols-4">
            <div className="rounded-xl border border-[#B8944F]/28 bg-[#F4ECDD] p-3.5">
              <dt className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">{localize("Ước tính mỗi phòng", "Estimate per room")}</dt>
              <dd className="mt-1.5 font-sans text-xl font-extrabold tabular-nums tracking-[-0.02em] text-[#0F2A43]">{formatVND(estimatedPrice)}</dd>
              {typeof hourlyPrice === "number" && (
                <dd className="mt-1 text-xs font-semibold text-[#66727C]">{localize(`Giờ đầu: ${formatVND(hourlyPrice)}`, `First hour: ${formatVND(hourlyPrice)}`)}</dd>
              )}
            </div>
            <div className="rounded-xl border border-[#527060]/16 bg-[#E5EEE9] p-3.5">
              <dt className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#527060]">{localize("Còn trống", "Available")}</dt>
              <dd className="mt-1.5 text-sm font-bold leading-5 text-[#0F2A43]">{localize(`${room.availableRooms ?? 0} phòng trong khung giờ này`, `${room.availableRooms ?? 0} rooms for this time window`)}</dd>
            </div>
            <div className="rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] p-3.5">
              <dt className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#66727C]">{localize("Sức chứa", "Capacity")}</dt>
              <dd className="mt-1.5 text-sm font-extrabold leading-5 text-[#0F2A43]">{localize(`Tối đa ${room.maxGuestsPerRoom ?? 0} khách / phòng`, `Up to ${room.maxGuestsPerRoom ?? 0} guests / room`)}</dd>
            </div>
            <div className="rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] p-3.5">
              <dt className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#66727C]">{localize("Thời lượng", "Duration")}</dt>
              <dd className="mt-1.5 text-sm font-extrabold leading-5 text-[#0F2A43]">{localize(`${room.totalHours ?? 0} giờ lưu trú`, `${room.totalHours ?? 0} stay hours`)}</dd>
            </div>
          </dl>

          <div className="mt-5 rounded-2xl border border-[#0F2A43]/10 bg-white p-3.5">
            <div className="flex items-center justify-between gap-4">
              <h3 className="text-xs font-extrabold uppercase tracking-[0.16em] text-[#0F2A43]">{localize("Tiện nghi của hạng phòng", "Room amenities")}</h3>
              <span className="shrink-0 rounded-full bg-[#EAE2D2] px-3 py-1 text-[10px] font-bold tabular-nums text-[#80632F]">{facilities.length}</span>
            </div>
            {facilities.length > 0 ? (
              <ul className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                {facilities.slice(0, 6).map((facility, index) => {
                  const facilityName = localize(facility.facilityName, facility.facilityNameEn) || localize("Tiện nghi", "Amenity");
                  return (
                    <li key={facility.id ?? `${facilityName}-${index}`} className="flex min-h-11 items-center gap-2.5 rounded-xl bg-[#F1F0EA] p-2 text-xs font-bold text-[#0F2A43]">
                      <span className="relative h-8 w-8 shrink-0 overflow-hidden rounded-lg bg-[#E5E9ED]">
                        {facility.imageUrl ? (
                          <ProgressiveImage src={facility.imageUrl} alt="" fill sizes="36px" className="object-cover" />
                        ) : (
                          <span aria-hidden="true" className="grid h-full w-full place-items-center text-[#80632F]">✓</span>
                        )}
                      </span>
                      <span className="line-clamp-2 leading-5">{facilityName}</span>
                    </li>
                  );
                })}
              </ul>
            ) : (
              <p className="mt-3 text-sm font-medium leading-6 text-[#66727C]">{localize("Danh sách tiện nghi của hạng phòng đang được cập nhật.", "Amenities for this room type are being updated.")}</p>
            )}
            {facilities.length > 6 && (
              <p className="mt-3 text-xs font-semibold text-[#66727C]">{localize(`Và ${facilities.length - 6} tiện nghi khác trong trang chi tiết.`, `Plus ${facilities.length - 6} more amenities on the full details page.`)}</p>
            )}
          </div>

          <label className="mt-5 flex min-h-12 items-center justify-between gap-4 rounded-xl border border-[#0F2A43]/12 bg-[#F1F0EA] px-4 text-sm font-bold text-[#0F2A43]">
            <span>{localize("Số phòng muốn chọn", "Rooms to select")}</span>
            <select
              aria-label={localize(`Số phòng ${room.typeName}`, `Rooms for ${room.typeNameEn || room.typeName}`)}
              value={selectedQuantity}
              onChange={(event) => onQuantityChange(Number(event.target.value))}
              className="h-10 w-20 rounded-lg border border-[#0F2A43]/12 bg-white px-3 text-center font-bold outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20"
            >
              {Array.from({ length: maximumQuantity + 1 }, (_, index) => index).map((value) => <option key={value} value={value}>{value}</option>)}
            </select>
          </label>

          <div className="mt-5 flex flex-wrap gap-3">
            <button type="button" onClick={onClose} className="inline-flex min-h-12 flex-1 items-center justify-center rounded-xl bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#091E30]">
              {localize("Xong", "Done")}
            </button>
            <Link href={`/rooms/${room.id}`} className="inline-flex min-h-12 flex-1 items-center justify-center rounded-xl border border-[#0F2A43]/16 px-6 text-center text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#EAE2D2]">
              {localize("Trang phòng chi tiết", "Full room details")}
            </Link>
          </div>
        </div>
      </section>
    </div>,
    portalRoot,
  );
}
