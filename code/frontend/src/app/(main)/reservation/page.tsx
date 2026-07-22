"use client";

import React, { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { apiClient } from "@/lib/api";
import Toast from "@/components/UI/Toast";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import DateTimeField from "@/components/forms/DateTimeField";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import GuestPageHero from "@/components/guest/GuestPageHero";
import ReservationRoomQuickViewModal, { type ReservationRoomQuickViewItem } from "@/components/guest/ReservationRoomQuickViewModal";
import { GALLERY_HERO_IMAGES } from "@/constants/content";
import { getPublicRoomTypes } from "@/lib/public-catalog";
import { getRoomGalleryImages } from "@/lib/room-gallery";

type ReservationRoomOption = ReservationRoomQuickViewItem;

interface AvailabilityRoomOption {
  roomTypeId: number;
  roomTypeName: string;
  roomTypeNameEn?: string;
  description?: string;
  descriptionEn?: string;
  imageUrl?: string;
  price?: number;
  pricePerNight?: number;
  pricePerHour?: number;
  estimatedPricePerRoom?: number;
  totalHours?: number;
  maxGuestsPerRoom?: number;
  availableRooms?: number;
}

interface RoomTypeFacilityOption {
  id?: number;
  facilityName?: string;
  facilityNameEn?: string;
  imageUrl?: string;
  type?: string;
}

interface RoomTypeCatalogOption {
  id: number;
  maxGuests?: number;
  facilities?: RoomTypeFacilityOption[];
}

const mapAvailabilityOptions = (
  data: AvailabilityRoomOption[],
  catalog: RoomTypeCatalogOption[] = [],
): ReservationRoomOption[] => {
  const catalogById = new Map(catalog.map((room) => [Number(room.id), room]));
  return data.map((room) => {
    const catalogRoom = catalogById.get(Number(room.roomTypeId));
    return {
      id: room.roomTypeId,
      typeName: room.roomTypeName,
      typeNameEn: room.roomTypeNameEn,
      description: room.description,
      descriptionEn: room.descriptionEn,
      imageUrl: room.imageUrl,
      gallery: getRoomGalleryImages(room.roomTypeName, room.roomTypeNameEn, room.imageUrl),
      price: room.estimatedPricePerRoom ?? room.price ?? room.pricePerNight ?? room.pricePerHour,
      pricePerHour: room.pricePerHour,
      estimatedPricePerRoom: room.estimatedPricePerRoom,
      totalHours: room.totalHours,
      maxGuestsPerRoom: room.maxGuestsPerRoom ?? catalogRoom?.maxGuests,
      availableRooms: room.availableRooms,
      facilities: catalogRoom?.facilities ?? [],
    };
  });
};

const loadAvailabilityOptions = async (checkIn: string, checkOut: string) => {
  const [response, catalog] = await Promise.all([
    apiClient.get(`/api/reservations/availability?checkIn=${checkIn}&checkOut=${checkOut}`),
    getPublicRoomTypes<RoomTypeCatalogOption>().catch(() => []),
  ]);
  const data: AvailabilityRoomOption[] = Array.isArray(response.data?.data) ? response.data.data : [];
  return mapAvailabilityOptions(data, catalog);
};

const formatDateTimeLocal = (date: Date) => {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
};

const formatVND = (value?: number) =>
  typeof value === "number"
    ? value.toLocaleString("vi-VN", { style: "currency", currency: "VND", maximumFractionDigits: 0 })
    : "Liên hệ";

export default function ReservationPage() {
  const { locale, localize } = useLanguage();
  const router = useRouter();
  const [checkIn, setCheckIn] = useState("");
  const [checkOut, setCheckOut] = useState("");
  const [adults, setAdults] = useState("2");
  const [childrenCount, setChildrenCount] = useState("0");
  const [rooms, setRooms] = useState<ReservationRoomOption[]>([]);
  const [selectedRooms, setSelectedRooms] = useState<Record<number, number>>({});
  const [isLoading, setIsLoading] = useState(true);
  const [isFormReady, setIsFormReady] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [preferredRoomTypeId, setPreferredRoomTypeId] = useState<number | null>(null);
  const [previewRoom, setPreviewRoom] = useState<ReservationRoomOption | null>(null);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);
  const resultsRef = useRef<HTMLElement>(null);
  const availabilityRequestRef = useRef(0);
  const autoSearchTimerRef = useRef<number | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const defaultCheckIn = new Date();
    defaultCheckIn.setSeconds(0, 0);
    defaultCheckIn.setMinutes(defaultCheckIn.getMinutes() + 30);
    const defaultCheckOut = new Date(defaultCheckIn);
    defaultCheckOut.setHours(defaultCheckOut.getHours() + 2);
    const requestedCheckIn = params.get("checkIn")?.trim() || "";
    const requestedCheckOut = params.get("checkOut")?.trim() || "";
    const requestedAdults = params.get("adults")?.trim() || "";
    const requestedChildren = params.get("children")?.trim() || "";
    const isDateTimeLocal = (value: string) => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)
      && !Number.isNaN(new Date(value).getTime());
    const initialCheckIn = isDateTimeLocal(requestedCheckIn)
      ? requestedCheckIn : formatDateTimeLocal(defaultCheckIn);
    const initialCheckOut = isDateTimeLocal(requestedCheckOut)
      && new Date(requestedCheckOut) > new Date(initialCheckIn)
      ? requestedCheckOut : formatDateTimeLocal(defaultCheckOut);
    const initialAdults = /^\d{1,2}$/.test(requestedAdults) && Number(requestedAdults) >= 1 && Number(requestedAdults) <= 12
      ? requestedAdults : "2";
    const initialChildren = /^\d{1,2}$/.test(requestedChildren) && Number(requestedChildren) <= 8
      ? requestedChildren : "0";
    const requestedRoomType = Number(params.get("roomTypeId"));
    const preferredId = Number.isFinite(requestedRoomType) && requestedRoomType > 0
      ? requestedRoomType : null;

    setCheckIn(initialCheckIn);
    setCheckOut(initialCheckOut);
    setAdults(initialAdults);
    setChildrenCount(initialChildren);
    setPreferredRoomTypeId(preferredId);
    setIsLoading(false);
    setIsFormReady(true);
  }, []);

  const totalGuests = useMemo(() => Number(adults || 0) + Number(childrenCount || 0), [adults, childrenCount]);
  const selectedRoomBreakdown = useMemo(
    () => rooms
      .map((room) => ({ room, quantity: selectedRooms[room.id] || 0 }))
      .filter(({ quantity }) => quantity > 0),
    [rooms, selectedRooms],
  );
  const selectedRoomTotal = useMemo(
    () => selectedRoomBreakdown.reduce((sum, item) => sum + item.quantity, 0),
    [selectedRoomBreakdown],
  );
  const canAutoCheckAvailability = useMemo(() => {
    if (!checkIn || !checkOut || totalGuests < 1) return false;
    const checkInDate = new Date(checkIn);
    const checkOutDate = new Date(checkOut);
    if (Number.isNaN(checkInDate.getTime()) || Number.isNaN(checkOutDate.getTime())) return false;
    return checkInDate > new Date() && checkOutDate > checkInDate;
  }, [checkIn, checkOut, totalGuests]);

  useEffect(() => {
    if (!isFormReady) return;

    const requestId = availabilityRequestRef.current + 1;
    availabilityRequestRef.current = requestId;
    if (autoSearchTimerRef.current !== null) {
      window.clearTimeout(autoSearchTimerRef.current);
      autoSearchTimerRef.current = null;
    }

    if (!canAutoCheckAvailability) {
      setIsLoading(false);
      setHasSearched(false);
      setRooms([]);
      setSelectedRooms({});
      setPreviewRoom(null);
      return;
    }

    setHasSearched(true);
    setIsLoading(true);
    setToast(null);
    autoSearchTimerRef.current = window.setTimeout(() => {
      autoSearchTimerRef.current = null;
      loadAvailabilityOptions(`${checkIn}:00`, `${checkOut}:00`)
        .then((availableOptions) => {
          if (availabilityRequestRef.current !== requestId) return;
          setRooms(availableOptions);
          setPreviewRoom(null);
          if (preferredRoomTypeId) {
            const preferred = availableOptions.find((room) => room.id === preferredRoomTypeId);
            setSelectedRooms(
              preferred && Number(preferred.availableRooms || 0) > 0
                ? { [preferredRoomTypeId]: 1 }
                : {},
            );
          } else {
            setSelectedRooms({});
          }
        })
        .catch(() => {
          if (availabilityRequestRef.current === requestId) {
            setToast({
              message: localize("Không thể tự động kiểm tra phòng trống. Vui lòng thử lại.", "Could not check availability automatically. Please try again."),
              type: "error",
            });
          }
        })
        .finally(() => {
          if (availabilityRequestRef.current === requestId) setIsLoading(false);
        });
    }, 550);

    return () => {
      if (autoSearchTimerRef.current !== null) {
        window.clearTimeout(autoSearchTimerRef.current);
        autoSearchTimerRef.current = null;
      }
    };
  }, [canAutoCheckAvailability, checkIn, checkOut, isFormReady, localize, preferredRoomTypeId]);

  const validateDates = () => {
    if (!checkIn || !checkOut) {
      setToast({ message: localize("Vui lòng chọn thời gian nhận và trả phòng.", "Please select check-in and check-out times."), type: "error" });
      return false;
    }

    const today = new Date();
    const checkInDate = new Date(checkIn);
    const checkOutDate = new Date(checkOut);

    if (checkInDate <= today) {
      setToast({ message: localize("Thời gian nhận phòng phải sau thời điểm hiện tại.", "Check-in must be after the current time."), type: "error" });
      return false;
    }

    if (checkOutDate <= checkInDate) {
      setToast({ message: localize("Thời gian trả phòng phải sau thời gian nhận phòng.", "Check-out must be after check-in."), type: "error" });
      return false;
    }

    if (totalGuests < 1) {
      setToast({ message: "Số khách phải từ 1 người trở lên.", type: "error" });
      return false;
    }

    return true;
  };

  const handleSearch = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!validateDates()) return;

    if (autoSearchTimerRef.current !== null) {
      window.clearTimeout(autoSearchTimerRef.current);
      autoSearchTimerRef.current = null;
    }
    const requestId = availabilityRequestRef.current + 1;
    availabilityRequestRef.current = requestId;

    setIsLoading(true);
    setHasSearched(true);
    setToast(null);

    try {
      const checkInDateTime = `${checkIn}:00`;
      const checkOutDateTime = `${checkOut}:00`;
      const availableOptions = await loadAvailabilityOptions(checkInDateTime, checkOutDateTime);
      if (availabilityRequestRef.current !== requestId) return;
      setRooms(availableOptions);
      setPreviewRoom(null);
      if (preferredRoomTypeId) {
        const preferred = availableOptions.find((room: ReservationRoomOption) => room.id === preferredRoomTypeId);
        if (preferred && Number(preferred.availableRooms || 0) > 0) {
          setSelectedRooms({ [preferredRoomTypeId]: 1 });
        } else {
          setSelectedRooms({});
        }
      } else {
        setSelectedRooms({});
      }
      window.requestAnimationFrame(() => {
        resultsRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
      });
    } catch {
      if (availabilityRequestRef.current === requestId) {
        setToast({ message: localize("Không thể kiểm tra phòng trống. Vui lòng thử lại.", "Could not check availability. Please try again."), type: "error" });
      }
    } finally {
      if (availabilityRequestRef.current === requestId) setIsLoading(false);
    }
  };

  const updateQuantity = (room: ReservationRoomOption, quantity: number) => {
    const safeQuantity = Math.max(0, Math.min(quantity, room.availableRooms ?? 99));
    setSelectedRooms((current) => {
      const next = { ...current };
      if (safeQuantity === 0) delete next[room.id];
      else next[room.id] = safeQuantity;
      return next;
    });
  };

  const continueBooking = () => {
    if (!validateDates()) return;
    const roomTypes = Object.entries(selectedRooms)
      .filter(([, quantity]) => quantity > 0)
      .map(([id, quantity]) => `${id}:${quantity}`)
      .join(",");
    if (!roomTypes) {
      setToast({ message: "Vui lòng chọn ít nhất một loại phòng.", type: "error" });
      return;
    }
    const params = new URLSearchParams({
      roomTypes,
      checkIn,
      checkOut,
      adults,
      children: childrenCount,
    });
    router.push(`/booking?${params.toString()}`);
  };

  return (
    <div className="min-h-screen bg-[#F1F0EA] text-[#0F2A43]">
      <GuestPageHero
        imageSrc={GALLERY_HERO_IMAGES.reservation}
        imageAlt={localize("Sảnh đón khách tại Luxury Hotel", "Luxury Hotel guest lobby")}
        eyebrow={localize("Đặt phòng trực tuyến", "Direct reservation")}
        title={localize("Đặt phòng phù hợp với kỳ nghỉ của bạn.", "Build the right reservation for your stay.")}
        description={localize("Chọn thời gian, số khách và số lượng từng hạng phòng. Lựa chọn được giữ nguyên khi bạn sang bước thanh toán.", "Choose your stay time, guests, and quantity for each room type. Your selections carry into payment.")}
        actions={(
          <ol className="grid w-full max-w-2xl gap-2 text-left sm:grid-cols-3" aria-label={localize("Ba bước đặt phòng", "Three booking steps")}>
            {(
              locale === "vi"
                ? [["01", "Chọn thời gian"], ["02", "Xem phòng trống"], ["03", "Chọn hạng phòng"]]
                : [["01", "Choose stay time"], ["02", "View availability"], ["03", "Select room types"]]
            ).map(([step, title]) => (
              <li key={step} className="flex min-h-14 items-center gap-3 rounded-xl border border-white/22 bg-[#091E30]/52 px-4 py-3 text-white shadow-[0_10px_26px_rgba(9,30,48,0.18)] backdrop-blur-sm">
                <span className="font-serif text-xl font-bold text-[#E4C77F]" aria-hidden="true">{step}</span>
                <strong className="min-w-0 text-sm font-bold leading-5 text-white">{title}</strong>
              </li>
            ))}
          </ol>
        )}
        contentClassName="-translate-y-3 md:-translate-y-4"
      />

      <section className="relative z-20 mx-auto -mt-16 max-w-6xl px-5 md:-mt-20 md:px-10">
        <form onSubmit={handleSearch} className="rounded-[1.75rem] border border-[#0F2A43]/14 bg-[#EAE2D2] p-5 shadow-[0_24px_70px_rgba(15,42,67,0.16)] md:p-7">
          <div className="grid gap-5 lg:grid-cols-2 lg:gap-6">
            <DateTimeField
              label={localize("Nhận phòng", "Check-in")}
              value={checkIn}
              onValueChange={setCheckIn}
            />
            <DateTimeField
              label={localize("Trả phòng", "Check-out")}
              value={checkOut}
              min={checkIn || undefined}
              onValueChange={setCheckOut}
            />
          </div>

          <div className="mt-6 grid gap-4 border-t border-[#0F2A43]/12 pt-5 sm:grid-cols-2 lg:grid-cols-[0.8fr_0.8fr_0.7fr_auto] lg:items-end">
            <label className="grid gap-2 text-sm font-semibold text-[#66727C]">
              {localize("Người lớn", "Adults")}
              <select
                value={adults}
                onChange={(event) => setAdults(event.target.value)}
                className="min-h-12 rounded-xl border border-[#0F2A43]/14 bg-[#FBFAF6] px-4 text-sm font-bold tabular-nums outline-none transition focus:border-[#B8944F] focus:ring-4 focus:ring-[#B8944F]/15"
              >
                {Array.from({ length: 12 }, (_, index) => index + 1).map((value) => <option key={value} value={value}>{value}</option>)}
              </select>
            </label>
            <label className="grid gap-2 text-sm font-semibold text-[#66727C]">
              {localize("Trẻ em", "Children")}
              <select
                value={childrenCount}
                onChange={(event) => setChildrenCount(event.target.value)}
                className="min-h-12 rounded-xl border border-[#0F2A43]/14 bg-[#FBFAF6] px-4 text-sm font-bold tabular-nums outline-none transition focus:border-[#B8944F] focus:ring-4 focus:ring-[#B8944F]/15"
              >
                {Array.from({ length: 9 }, (_, index) => index).map((value) => <option key={value} value={value}>{value}</option>)}
              </select>
            </label>
            <div className="flex min-h-12 items-center justify-between rounded-xl border border-[#0F2A43]/10 bg-[#E5EEE9] px-4 lg:block lg:py-2">
              <span className="text-xs font-semibold text-[#66727C]">{localize("Tổng số khách", "Total guests")}</span>
              <strong className="text-lg font-bold tabular-nums text-[#0F2A43] lg:mt-0.5 lg:block">{totalGuests}</strong>
            </div>
            <button
              type="submit"
              disabled={isLoading}
              aria-busy={isLoading}
              className="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-[#0F2A43] px-7 text-sm font-bold text-white transition hover:bg-[#091E30] active:translate-y-px disabled:cursor-wait disabled:opacity-70 disabled:hover:bg-[#0F2A43] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2 focus-visible:ring-offset-[#EAE2D2]"
            >
              {isLoading && <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-white/35 border-t-white" />}
              {isLoading
                ? localize("Đang kiểm tra", "Checking")
                : localize("Kiểm tra", "Check")}
            </button>
          </div>
        </form>
      </section>

      <section id="availability-results" ref={resultsRef} className="deferred-section mx-auto max-w-7xl scroll-mt-24 px-6 py-20 md:px-10">
        <div className="mb-10 flex flex-col justify-between gap-4 md:flex-row md:items-end">
          <div>
            <p className="mb-4 text-xs font-bold uppercase tracking-[0.25em] text-[#80632F]">
              {hasSearched ? localize("Lựa chọn còn trống", "Available choices") : localize("Loại phòng", "Room types")}
            </p>
            <h2 className="font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">
              {localize("Chọn phòng phù hợp", "Choose the right rooms")}
            </h2>
          </div>
          <p className="text-sm font-semibold text-[#66727C]">{totalGuests} {localize("khách", "guests")}</p>
        </div>

        {isLoading ? (
          <div className="motion-stagger grid gap-6 md:grid-cols-2 xl:grid-cols-3">
            {[0, 1, 2].map((item) => (
              <div key={item} className="overflow-hidden rounded-[2rem] bg-white shadow-sm">
                <div className="skeleton-surface aspect-[16/11]" />
                <div className="space-y-4 p-6">
                  <div className="skeleton-surface h-5 w-2/3" />
                  <div className="skeleton-surface h-4 w-full" />
                  <div className="skeleton-surface h-12 w-full rounded-[1.25rem]" />
                </div>
              </div>
            ))}
          </div>
        ) : !hasSearched ? (
          <div className="rounded-[1.5rem] border border-dashed border-[#0F2A43]/16 bg-white/55 px-6 py-10 text-center text-sm font-semibold text-[#66727C]">
            {localize("Chọn thời gian nhận và trả phòng hợp lệ để xem phòng còn trống.", "Choose valid check-in and check-out times to see available rooms.")}
          </div>
        ) : rooms.length === 0 ? (
          <div className="rounded-[2rem] border border-[#0F2A43]/10 bg-white px-6 py-16 text-center">
            <h3 className="font-serif text-3xl font-bold text-[#0F2A43]">{localize("Chưa có phòng phù hợp", "No suitable rooms found")}</h3>
            <p className="mt-3 text-sm text-[#66727C]">{localize("Thử đổi thời gian lưu trú hoặc giảm số lượng khách.", "Try changing the stay time or reducing the guest count.")}</p>
          </div>
        ) : (
          <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-3">
            {rooms.map((room) => (
              <article key={room.id} className={`group flex h-full flex-col overflow-hidden rounded-[2rem] bg-white shadow-sm ring-offset-4 transition duration-300 hover:-translate-y-1 hover:shadow-xl ${selectedRooms[room.id] ? "ring-2 ring-[#B8944F]" : ""}`}>
                <button
                  type="button"
                  aria-haspopup="dialog"
                  aria-label={localize(`Xem nhanh ${room.typeName}`, `Quick view for ${room.typeNameEn || room.typeName}`)}
                  onClick={() => setPreviewRoom(room)}
                  className="flex flex-1 flex-col text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F]"
                >
                <div className="relative aspect-[16/11] overflow-hidden rounded-[2rem] bg-[#E5E9ED]">
                  {room.imageUrl ? (
                    <ProgressiveImage
                      src={room.imageUrl}
                      alt={localize(room.typeName, room.typeNameEn)}
                      fill
                      sizes="(min-width: 1280px) 33vw, (min-width: 768px) 50vw, 100vw"
                      className="object-cover group-hover:scale-105"
                    />
                  ) : (
                    <div className="flex h-full w-full items-center justify-center text-sm font-semibold text-[#66727C]">{localize("Chưa có ảnh", "No image")}</div>
                  )}
                  <div className="absolute right-4 top-4 rounded-2xl border border-white/70 bg-[#FBFAF6]/96 px-4 py-2.5 text-right text-[#0F2A43] shadow-[0_10px_30px_rgba(9,30,48,0.2)] backdrop-blur-sm">
                    <span className="block text-[9px] font-extrabold uppercase tracking-[0.15em] text-[#80632F]">{localize("Giá giờ đầu", "First hour")}</span>
                    <strong className="mt-0.5 block font-sans text-lg font-extrabold tabular-nums tracking-[-0.02em] text-[#0F2A43]">{formatVND(room.pricePerHour ?? room.price)}</strong>
                  </div>
                </div>
                <div className="flex flex-1 flex-col px-6 pb-4 pt-6">
                  <div className="flex items-start justify-between gap-4">
                    <h3 className="font-serif text-2xl font-bold text-[#0F2A43]">{localize(room.typeName, room.typeNameEn)}</h3>
                    {typeof room.availableRooms === "number" && (
                      <span className="rounded-full bg-[#EAE2D2] px-3 py-1 text-[10px] font-bold uppercase tracking-[0.14em] text-[#80632F]">
                        {localize(`Còn ${room.availableRooms}`, `${room.availableRooms} left`)}
                      </span>
                    )}
                  </div>
                  <p className="mt-3 line-clamp-3 text-sm font-light leading-7 text-[#66727C]">
                    {localize(room.description, room.descriptionEn) || localize("Không gian nghỉ dưỡng yên tĩnh với đầy đủ tiện nghi cần thiết cho chuyến đi.", "A calm room with the essential facilities for your stay.")}
                  </p>
                  <span className="mt-5 inline-flex items-center gap-2 text-xs font-bold uppercase tracking-[0.16em] text-[#80632F]">
                    {localize("Xem nhanh chi tiết", "Quick view")} <span aria-hidden="true" className="text-base transition group-hover:translate-x-1">→</span>
                  </span>
                </div>
                </button>
                <div className="mx-6 mb-6 mt-auto flex items-center justify-between gap-4 rounded-[1.5rem] bg-[#F0EADF] p-3">
                    <span className="text-xs font-bold uppercase tracking-[0.14em] text-[#66727C]">{localize("Số phòng", "Rooms")}</span>
                    <select
                      aria-label={localize(`Số phòng ${room.typeName}`, `Rooms for ${room.typeNameEn || room.typeName}`)}
                      value={selectedRooms[room.id] || 0}
                      onChange={(event) => updateQuantity(room, Number(event.target.value))}
                      className="h-10 w-20 rounded-xl border border-[#0F2A43]/10 bg-white px-3 text-center font-bold outline-none focus:border-[#B8944F]"
                    >
                      {Array.from({ length: Math.min(room.availableRooms ?? 10, 20) + 1 }, (_, index) => index).map((value) => <option key={value} value={value}>{value}</option>)}
                    </select>
                </div>
              </article>
            ))}
          </div>
        )}

        {selectedRoomBreakdown.length > 0 && (
          <div className="sticky bottom-5 z-30 mt-10 flex flex-col items-center justify-between gap-4 rounded-[1.75rem] bg-[#0F2A43] px-6 py-5 text-white shadow-2xl md:flex-row">
            <div>
              <p className="text-sm font-semibold">
                {localize(`Đã chọn ${selectedRoomTotal} phòng thuộc ${selectedRoomBreakdown.length} loại`, `${selectedRoomTotal} rooms selected across ${selectedRoomBreakdown.length} types`)}
              </p>
              <ul className="mt-2 flex flex-wrap gap-2" aria-label={localize("Chi tiết phòng đã chọn", "Selected room details")}>
                {selectedRoomBreakdown.map(({ room, quantity }) => {
                  const name = localize(room.typeName, room.typeNameEn);
                  const viLabel = /^phòng\s/i.test(name) ? `${quantity} ${name}` : `${quantity} Phòng ${name}`;
                  const enLabel = `${quantity} ${name}${quantity === 1 ? " room" : " rooms"}`;
                  return <li key={room.id} className="rounded-full border border-white/18 bg-white/10 px-3 py-1 text-xs font-bold text-[#FFFDF8]">{localize(viLabel, enLabel)}</li>;
                })}
              </ul>
            </div>
            <button onClick={continueBooking} className="rounded-[1.25rem] bg-[#B8944F] px-7 py-4 text-xs font-bold uppercase tracking-[0.18em] text-[#0F2A43]">
              {localize("Tiếp tục đặt phòng", "Continue booking")}
            </button>
          </div>
        )}
      </section>

      <ReservationRoomQuickViewModal
        room={previewRoom}
        selectedQuantity={previewRoom ? selectedRooms[previewRoom.id] || 0 : 0}
        onQuantityChange={(quantity) => {
          if (previewRoom) updateQuantity(previewRoom, quantity);
        }}
        onClose={() => setPreviewRoom(null)}
      />
      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
