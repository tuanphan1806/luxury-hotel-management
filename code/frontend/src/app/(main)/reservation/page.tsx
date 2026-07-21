"use client";

import React, { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { apiClient } from "@/lib/api";
import Toast from "@/components/UI/Toast";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import DateTimeField from "@/components/forms/DateTimeField";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import GuestPageHero from "@/components/guest/GuestPageHero";
import { GALLERY_HERO_IMAGES } from "@/constants/content";

interface ReservationRoomOption {
  id: number;
  typeName: string;
  typeNameEn?: string;
  description?: string;
  descriptionEn?: string;
  imageUrl?: string;
  price?: number;
  availableRooms?: number;
}

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
  availableRooms?: number;
}

const mapAvailabilityOptions = (data: AvailabilityRoomOption[]): ReservationRoomOption[] =>
  data.map((room) => ({
    id: room.roomTypeId,
    typeName: room.roomTypeName,
    typeNameEn: room.roomTypeNameEn,
    description: room.description,
    descriptionEn: room.descriptionEn,
    imageUrl: room.imageUrl,
    price: room.price || room.pricePerNight || room.pricePerHour,
    availableRooms: room.availableRooms,
  }));

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
  const [hasSearched, setHasSearched] = useState(false);
  const [preferredRoomTypeId, setPreferredRoomTypeId] = useState<number | null>(null);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);
  const resultsRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const defaultCheckIn = new Date();
    defaultCheckIn.setSeconds(0, 0);
    defaultCheckIn.setMinutes(defaultCheckIn.getMinutes() + 30);
    defaultCheckIn.setMinutes(Math.ceil(defaultCheckIn.getMinutes() / 15) * 15);
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

    if (params.get("search") !== "1") {
      setIsLoading(false);
      return;
    }

    let cancelled = false;
    setHasSearched(true);
    apiClient.get(`/api/reservations/availability?checkIn=${initialCheckIn}:00&checkOut=${initialCheckOut}:00`)
      .then((res) => {
        if (cancelled) return;
        const data: AvailabilityRoomOption[] = Array.isArray(res.data?.data) ? res.data.data : [];
        const availableOptions = mapAvailabilityOptions(data);
        setRooms(availableOptions);
        if (preferredId) {
          const preferred = availableOptions.find((room) => room.id === preferredId);
          if (preferred && Number(preferred.availableRooms || 0) > 0) {
            setSelectedRooms({ [preferredId]: 1 });
          }
        }
      })
      .catch(() => {
        if (!cancelled) {
          setToast({ message: "Không thể kiểm tra phòng trống. Vui lòng thử lại.", type: "error" });
        }
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const totalGuests = useMemo(() => Number(adults || 0) + Number(childrenCount || 0), [adults, childrenCount]);

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

    setIsLoading(true);
    setHasSearched(true);

    try {
      const checkInDateTime = `${checkIn}:00`;
      const checkOutDateTime = `${checkOut}:00`;
      const res = await apiClient.get(`/api/reservations/availability?checkIn=${checkInDateTime}&checkOut=${checkOutDateTime}`);
      const data: AvailabilityRoomOption[] = Array.isArray(res.data?.data) ? res.data.data : [];

      const availableOptions = mapAvailabilityOptions(data);
      setRooms(availableOptions);
      if (preferredRoomTypeId) {
        const preferred = availableOptions.find((room: ReservationRoomOption) => room.id === preferredRoomTypeId);
        if (preferred && Number(preferred.availableRooms || 0) > 0) setSelectedRooms({ [preferredRoomTypeId]: 1 });
      }
      window.requestAnimationFrame(() => {
        resultsRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
      });
    } catch {
      setToast({ message: "Không thể kiểm tra phòng trống. Vui lòng thử lại.", type: "error" });
    } finally {
      setIsLoading(false);
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
        title={localize("Đặt phòng phù hợp với kỳ nghỉ của bạn.", "Build the right reservation for your stay.")}
        description={localize("Chọn ngày, giờ, số khách và nhiều hạng phòng trong cùng một đơn. Hệ thống sẽ giữ nguyên lựa chọn khi bạn chuyển sang thanh toán.", "Choose dates, times, guests, and multiple room types in one booking. Your selections carry into payment.")}
      />

      <section className="relative z-20 mx-auto -mt-14 max-w-6xl px-5 md:px-10">
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
            <button className="min-h-12 rounded-xl bg-[#0F2A43] px-7 text-sm font-bold text-white transition hover:bg-[#091E30] active:translate-y-px focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2 focus-visible:ring-offset-[#EAE2D2]">
              {localize("Kiểm tra phòng", "Check rooms")}
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
          <div className="grid gap-6 rounded-[1.5rem] border border-[#0F2A43]/10 bg-[#E5E9ED] p-6 md:grid-cols-3 md:p-8">
            {(locale === "vi" ? [['1','Chọn thời gian','Nhập chính xác giờ nhận và giờ dự kiến trả phòng.'],['2','Kiểm tra phòng trống','Hệ thống trả về số lượng còn trống của từng loại phòng.'],['3','Chọn và tiếp tục','Có thể chọn nhiều loại phòng trước khi sang bước thông tin khách.']] : [['1','Choose dates','Enter the exact planned check-in and check-out times.'],['2','Check availability','The system shows availability for each room type.'],['3','Select and continue','Choose multiple room types before entering guest details.']]).map(([step,title,description]) => <div key={step} className="border-l-2 border-[#B8944F] pl-4"><p className="text-xs font-bold text-[#80632F]">{localize("Bước", "Step")} {step}</p><h3 className="mt-2 text-lg font-bold text-[#0F2A43]">{title}</h3><p className="mt-2 text-sm leading-6 text-[#66727C]">{description}</p></div>)}
          </div>
        ) : rooms.length === 0 ? (
          <div className="rounded-[2rem] border border-[#0F2A43]/10 bg-white px-6 py-16 text-center">
            <h3 className="font-serif text-3xl font-bold text-[#0F2A43]">{localize("Chưa có phòng phù hợp", "No suitable rooms found")}</h3>
            <p className="mt-3 text-sm text-[#66727C]">{localize("Thử đổi thời gian lưu trú hoặc giảm số lượng khách.", "Try changing the stay time or reducing the guest count.")}</p>
          </div>
        ) : (
          <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-3">
            {rooms.map((room) => (
              <article key={room.id} className="group flex h-full flex-col overflow-hidden rounded-[2rem] bg-white shadow-sm transition duration-300 hover:-translate-y-1 hover:shadow-xl">
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
                  <div className="absolute right-4 top-4 rounded-[1.25rem] bg-[#0F2A43] px-4 py-2 font-serif text-base font-bold text-[#B8944F] shadow-lg">
                    {formatVND(room.price)}
                  </div>
                </div>
                <div className="flex flex-1 flex-col p-6">
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
                  <div className="mt-auto flex items-center justify-between gap-4 rounded-[1.5rem] bg-[#F0EADF] p-3">
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
                </div>
              </article>
            ))}
          </div>
        )}

        {Object.keys(selectedRooms).length > 0 && (
          <div className="sticky bottom-5 z-30 mt-10 flex flex-col items-center justify-between gap-4 rounded-[1.75rem] bg-[#0F2A43] px-6 py-5 text-white shadow-2xl md:flex-row">
            <p className="text-sm font-semibold">
              {localize(`Đã chọn ${Object.values(selectedRooms).reduce((sum, quantity) => sum + quantity, 0)} phòng thuộc ${Object.keys(selectedRooms).length} loại`, `${Object.values(selectedRooms).reduce((sum, quantity) => sum + quantity, 0)} rooms selected across ${Object.keys(selectedRooms).length} types`)}
            </p>
            <button onClick={continueBooking} className="rounded-[1.25rem] bg-[#B8944F] px-7 py-4 text-xs font-bold uppercase tracking-[0.18em] text-[#0F2A43]">
              {localize("Tiếp tục đặt phòng", "Continue booking")}
            </button>
          </div>
        )}
      </section>

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
