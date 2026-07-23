"use client";

import React, { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { GALLERY_HERO_IMAGES, HOME_CONTENT } from "@/constants/content";
import Toast from "@/components/UI/Toast";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import DateTimeField from "@/components/forms/DateTimeField";
import { getPublicFacilities, getPublicGalleries, getPublicRoomTypes } from "@/lib/public-catalog";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import FacilityDetailModal, { type FacilityDetailItem } from "@/components/guest/FacilityDetailModal";

type FacilityItem = FacilityDetailItem;

interface RoomTypeItem {
  id?: number;
  typeName?: string;
  typeNameEn?: string;
  title?: string;
  description?: string;
  descriptionEn?: string;
  price?: number | string;
  imageUrl?: string;
  image?: string;
}

interface GalleryItem {
  id?: number;
  title?: string;
  titleEn?: string;
  imageUrl?: string;
  image?: string;
}

function CarouselControls({
  previousLabel,
  nextLabel,
  onPrevious,
  onNext,
  testIdPrefix,
  disabled = false,
}: {
  previousLabel: string;
  nextLabel: string;
  onPrevious: () => void;
  onNext: () => void;
  testIdPrefix: string;
  disabled?: boolean;
}) {
  const buttonClass = "group pointer-events-auto absolute top-1/2 z-30 inline-flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full border border-[#0F2A43]/18 bg-[#FBFAF6]/94 text-[#0F2A43] shadow-[0_10px_28px_rgba(15,42,67,0.2)] backdrop-blur-sm transition-[background-color,border-color,box-shadow,transform] duration-200 hover:scale-105 hover:border-[#B8944F]/70 hover:bg-[#EAE2D2] hover:shadow-[0_12px_32px_rgba(15,42,67,0.26)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-35 disabled:hover:scale-100 motion-reduce:transition-none motion-reduce:hover:scale-100";
  return (
    <div className="pointer-events-none absolute inset-0 z-30 hidden md:block" role="group" aria-label={`${previousLabel} / ${nextLabel}`}>
      <button data-testid={`${testIdPrefix}-previous`} type="button" aria-label={previousLabel} onClick={onPrevious} disabled={disabled} className={`${buttonClass} left-0`}><svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4 transition-transform duration-200 group-hover:-translate-x-0.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M19 12H5m6-6-6 6 6 6" /></svg></button>
      <button data-testid={`${testIdPrefix}-next`} type="button" aria-label={nextLabel} onClick={onNext} disabled={disabled} className={`${buttonClass} right-0`}><svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4 transition-transform duration-200 group-hover:translate-x-0.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14m-6-6 6 6-6 6" /></svg></button>
    </div>
  );
}

export default function HomePage() {
  const router = useRouter();
  const { locale, localeTag, localize } = useLanguage();
  const [facilities, setFacilities] = useState<FacilityItem[]>([]);
  const [rooms, setRooms] = useState<RoomTypeItem[]>([]);
  const [gallery, setGallery] = useState<GalleryItem[]>([]);
  const [galleryStart, setGalleryStart] = useState(0);
  const [galleryDirection, setGalleryDirection] = useState<"previous" | "next">("next");
  const [checkIn, setCheckIn] = useState("");
  const [checkOut, setCheckOut] = useState("");
  const [guestCount, setGuestCount] = useState("2");
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);
  const [selectedFacility, setSelectedFacility] = useState<FacilityItem | null>(null);
  const facilityScrollerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const defaultCheckIn = new Date();
    defaultCheckIn.setSeconds(0, 0);
    defaultCheckIn.setMinutes(defaultCheckIn.getMinutes() + 30);
    defaultCheckIn.setMinutes(Math.ceil(defaultCheckIn.getMinutes() / 15) * 15);
    const defaultCheckOut = new Date(defaultCheckIn);
    defaultCheckOut.setHours(defaultCheckOut.getHours() + 2);
    const format = (date: Date) => {
      const offset = date.getTimezoneOffset() * 60_000;
      return new Date(date.getTime() - offset).toISOString().slice(0, 16);
    };
    setCheckIn(format(defaultCheckIn));
    setCheckOut(format(defaultCheckOut));
  }, []);

  useEffect(() => {
    Promise.allSettled([
      getPublicFacilities<FacilityItem>(),
      getPublicRoomTypes<RoomTypeItem>(),
      getPublicGalleries<GalleryItem>(),
    ]).then(([facilityRes, roomRes, galleryRes]) => {
      if (facilityRes.status === "fulfilled") {
        setFacilities(facilityRes.value);
      } else {
        setFacilities([]);
      }

      if (roomRes.status === "fulfilled") {
        setRooms(roomRes.value);
      } else {
        setRooms([]);
      }

      if (galleryRes.status === "fulfilled") {
        setGallery(galleryRes.value);
      }
    });
  }, []);

  const visibleFacilities = useMemo(
    () =>
      facilities
        .filter((item) => item.imageUrl || item.image || item.icon),
    [facilities]
  );

  const favoriteRooms = useMemo(() => rooms.slice(0, 3), [rooms]);
  const galleryImages = useMemo(() => gallery.filter((item) => item.imageUrl || item.image), [gallery]);
  const visibleGalleryImages = useMemo(() => {
    if (!galleryImages.length) return [];
    return Array.from({ length: Math.min(3, galleryImages.length) }, (_, index) => galleryImages[(galleryStart + index) % galleryImages.length]);
  }, [galleryImages, galleryStart]);
  const stayIntents = [
    {
      href: "/reservation",
      image: GALLERY_HERO_IMAGES.reservation,
      eyebrow: localize("Theo giờ", "By the hour"),
      title: localize("Nghỉ ngắn linh hoạt", "A flexible short stay"),
      description: localize("Chọn chính xác giờ nhận, giờ trả và xem giá trước khi tiếp tục.", "Choose exact arrival and departure times and review the price before continuing."),
    },
    {
      href: "/rooms",
      image: GALLERY_HERO_IMAGES.rooms,
      eyebrow: localize("Khám phá", "Discover"),
      title: localize("Cuối tuần thư thái", "A slower weekend"),
      description: localize("So sánh không gian, tiện nghi và đánh giá của từng hạng phòng.", "Compare the space, facilities, and reviews for every room type."),
    },
    {
      href: "/reservation",
      image: GALLERY_HERO_IMAGES.facilities,
      eyebrow: localize("Đi cùng nhau", "Stay together"),
      title: localize("Gia đình hoặc nhóm nhỏ", "Families and small groups"),
      description: localize("Chọn nhiều hạng phòng và số lượng trong cùng một đơn đặt phòng.", "Choose several room types and quantities in a single reservation."),
    },
  ];
  const moveGallery = (direction: "previous" | "next") => {
    if (galleryImages.length <= 1) return;
    setGalleryDirection(direction);
    setGalleryStart((current) => direction === "next" ? (current + 1) % galleryImages.length : (current - 1 + galleryImages.length) % galleryImages.length);
  };

  const scrollFacilities = (direction: "previous" | "next") => {
    const scroller = facilityScrollerRef.current;
    if (!scroller) return;
    scroller.scrollBy({ left: (direction === "next" ? 1 : -1) * Math.max(320, scroller.clientWidth * 0.72), behavior: "smooth" });
  };

  const handleGalleryScroll = (event: React.UIEvent<HTMLDivElement>) => {
    const scroller = event.currentTarget;
    if (!scroller.clientWidth) return;
    const nextIndex = Math.min(galleryImages.length - 1, Math.max(0, Math.round(scroller.scrollLeft / scroller.clientWidth)));
    setGalleryStart((currentIndex) => {
      if (currentIndex === nextIndex) return currentIndex;
      setGalleryDirection(nextIndex > currentIndex ? "next" : "previous");
      return nextIndex;
    });
  };

  const handleQuickSearch = (event: React.FormEvent) => {
    event.preventDefault();
    if (!checkIn || !checkOut) {
      setToast({ message: localize("Vui lòng chọn thời gian nhận và trả phòng.", "Please select check-in and check-out times."), type: "error" });
      return;
    }

    const checkInDate = new Date(checkIn);
    const checkOutDate = new Date(checkOut);
    const today = new Date();

    if (checkInDate <= today) {
      setToast({ message: localize("Thời gian nhận phòng phải sau thời điểm hiện tại.", "Check-in must be after the current time."), type: "error" });
      return;
    }

    if (checkOutDate <= checkInDate) {
      setToast({ message: localize("Thời gian trả phòng phải sau thời gian nhận phòng.", "Check-out must be after check-in."), type: "error" });
      return;
    }

    const guests = Number(guestCount);
    if (!Number.isInteger(guests) || guests < 1) {
      setToast({ message: "Số khách phải từ 1 người trở lên.", type: "error" });
      return;
    }

    const params = new URLSearchParams({
      checkIn,
      checkOut,
      adults: String(guests),
      children: "0",
      search: "1",
    });
    router.push(`/reservation?${params.toString()}#availability-results`);
  };

  return (
    <div className="home-color-story text-[#0F2A43]">
      <section className="relative min-h-[100dvh] overflow-hidden bg-[#E5E9ED] lg:min-h-[780px]">
        <ProgressiveImage
          src={HOME_CONTENT.hero.bg}
          alt={localize("Không gian sảnh Luxury Hotel", "Luxury Hotel lobby")}
          fill
          priority
          quality={92}
          sizes="100vw"
          className="object-cover"
          loaderClassName="hero-image-loading-surface"
        />
        <div className="absolute inset-0 bg-[#091E30]/8" />
        <div className="absolute inset-0 bg-gradient-to-r from-[#091E30]/64 via-[#0F2A43]/20 to-transparent" />
        <div className="absolute inset-x-0 bottom-0 h-48 bg-gradient-to-t from-[#091E30]/18 to-transparent" />

        <div className="relative z-10 mx-auto flex min-h-[100dvh] max-w-7xl flex-col px-5 pb-20 pt-32 sm:px-6 sm:pb-24 sm:pt-36 lg:min-h-[760px] lg:justify-center lg:px-10 lg:pb-28">
          <div className="max-w-3xl">
            <div className="mb-5 flex flex-wrap items-center gap-3 text-xs font-semibold text-[#D8C398]">
              <span className="uppercase tracking-[0.26em]">{localize("Đặt phòng trực tiếp", "Book direct")}</span>
              <span aria-hidden="true" className="h-px w-8 bg-[#B8944F]/70" />
              <span className="flex items-center gap-2 text-white/76"><span className="h-2 w-2 rounded-full bg-emerald-400" /> Thanh toán QR</span>
            </div>
            <h1 className="font-serif text-[2.55rem] font-bold leading-[1.14] text-white sm:text-5xl lg:text-7xl">
              {localize("Một kỳ nghỉ được chuẩn bị chỉn chu từ trước.", "A stay thoughtfully prepared before you arrive.")}
            </h1>
            <p className="mt-6 max-w-xl text-base leading-8 text-[#F5F1E8]/94 drop-shadow-[0_1px_8px_rgba(0,0,0,0.32)] md:text-lg">
              {localize("Xem đúng số phòng còn trống, chọn nhiều hạng phòng trong một đơn và theo dõi rõ từng bước thanh toán, nhận phòng, lưu trú và trả phòng.", "See live availability, combine room types in one booking, and follow every step from payment to check-in and checkout.")}
            </p>
            <div className="mt-7 flex flex-col gap-3 sm:flex-row">
              <Link href="/reservation" className="inline-flex min-h-12 items-center justify-center rounded-xl bg-[#FBFAF6] px-6 text-sm font-bold text-[#0F2A43] transition hover:-translate-y-0.5 hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D8C398] focus-visible:ring-offset-2 focus-visible:ring-offset-[#091E30]">
                {localize("Kiểm tra phòng trống", "Check availability")}
              </Link>
              <Link href="/rooms" className="inline-flex min-h-12 items-center justify-center rounded-xl border border-white/35 bg-white/5 px-6 text-sm font-bold text-white transition hover:-translate-y-0.5 hover:border-[#D8C398]/70 hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D8C398] focus-visible:ring-offset-2 focus-visible:ring-offset-[#091E30]">
                {localize("Khám phá hạng phòng", "Explore room types")}
              </Link>
            </div>

            <dl data-testid="home-hero-features" className="mt-9 hidden max-w-2xl grid-cols-3 divide-x divide-white/28 overflow-hidden rounded-xl border border-white/18 bg-[#091E30]/26 px-2 py-4 shadow-[0_14px_38px_rgba(3,12,28,0.16)] backdrop-blur-[2px] lg:grid">
              {[
                [localize("Rõ ràng", "Transparent"), localize("Lịch trống và giá", "Availability and pricing")],
                [localize("Linh hoạt", "Flexible"), localize("Khung giờ lưu trú", "Stay time windows")],
                [localize("Chu đáo", "Attentive"), localize("Hỗ trợ tại quầy", "Front desk support")],
              ].map(([title, detail]) => (
                <div key={title} className="px-5 first:pl-0">
                  <dt className="font-serif text-2xl font-bold text-[#FFE5A8] drop-shadow-[0_1px_8px_rgba(0,0,0,0.5)]">{title}</dt>
                  <dd className="mt-1.5 text-[11px] font-bold uppercase tracking-[0.16em] text-[#FFFDF8] drop-shadow-[0_1px_7px_rgba(0,0,0,0.58)]">{detail}</dd>
                </div>
              ))}
            </dl>
          </div>

        </div>
      </section>

      <div data-testid="home-availability-shell" className="relative z-20 mx-auto -mt-10 w-full max-w-7xl px-5 sm:-mt-12 sm:px-6 lg:px-10">
        <form
          data-testid="home-availability-form"
          onSubmit={handleQuickSearch}
          className="grid gap-3 rounded-2xl border border-white/45 bg-[#FBFAF6] p-4 shadow-[0_24px_60px_rgba(3,12,28,0.3)] sm:grid-cols-2 sm:p-5 lg:grid-cols-[1fr_1fr_0.8fr_auto] lg:items-end"
        >
            <DateTimeField
              label={localize("Nhận phòng", "Check in")}
              value={checkIn}
              onValueChange={setCheckIn}
            />
            <DateTimeField
              label={localize("Trả phòng", "Check out")}
              value={checkOut}
              min={checkIn || undefined}
              onValueChange={setCheckOut}
            />
            <label className="grid gap-2 text-xs font-bold uppercase tracking-[0.18em] text-[#66727C]">
              {localize("Số khách", "Guests")}
              <select
                value={guestCount}
                onChange={(event) => setGuestCount(event.target.value)}
                className="min-h-12 rounded-lg border border-[#0F2A43]/12 bg-white px-4 text-sm font-semibold text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20"
              >
                {Array.from({ length: 12 }, (_, index) => index + 1).map((value) => <option key={value} value={value}>{value}</option>)}
              </select>
            </label>
            <button className="min-h-12 rounded-lg bg-[#0F2A43] px-7 text-xs font-bold uppercase tracking-[0.16em] text-white transition hover:bg-[#091E30] active:translate-y-px focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2">
              {localize("Tìm phòng", "Search rooms")}
            </button>
        </form>
      </div>

      <section className="home-section-surface deferred-section px-6 pb-10 pt-16 md:px-10 md:pb-14 md:pt-20" aria-labelledby="stay-intent-title">
        <div className="mx-auto max-w-7xl">
          <div className="mb-9 grid gap-4 lg:grid-cols-[0.8fr_1.2fr] lg:items-end">
            <div>
              <p className="text-xs font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Chọn theo nhịp chuyến đi", "Choose your stay rhythm")}</p>
              <h2 id="stay-intent-title" className="mt-4 font-serif text-4xl font-bold leading-tight text-[#0F2A43] md:text-5xl">{localize("Bắt đầu từ điều bạn thực sự cần.", "Start with what you actually need.")}</h2>
            </div>
            <p className="max-w-2xl text-sm font-medium leading-7 text-[#66727C] lg:justify-self-end">{localize("Đây là các lối khám phá, không phải gói giá cố định. Tồn phòng và tổng tiền luôn được tính lại theo ngày giờ, số khách và số lượng bạn chọn.", "These are discovery paths, not fixed packages. Availability and totals are always recalculated from your dates, guests, and quantities.")}</p>
          </div>

          <div className="motion-stagger grid gap-4 md:grid-cols-3">
            {stayIntents.map((intent) => (
              <Link key={intent.title} href={intent.href} className="guest-media-lift group relative min-h-[21rem] overflow-hidden rounded-[1.75rem] bg-[#E5E9ED]">
                <ProgressiveImage src={intent.image} fallbackSrc={HOME_CONTENT.hero.bg} alt={intent.title} fill sizes="(min-width: 768px) 33vw, 100vw" className="object-cover group-hover:scale-[1.035]" />
                <div className="absolute inset-0 bg-gradient-to-t from-[#091E30]/92 via-[#0F2A43]/24 to-transparent" />
                <div className="absolute inset-x-0 bottom-0 p-6 text-white">
                  <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#D8C398]">{intent.eyebrow}</p>
                  <h3 className="mt-2 font-serif text-2xl font-bold">{intent.title}</h3>
                  <p className="mt-3 text-sm font-medium leading-6 text-white/80">{intent.description}</p>
                  <span className="mt-5 inline-flex items-center gap-2 text-xs font-bold uppercase tracking-[0.16em] text-[#E4C77F]">{localize("Khám phá", "Explore")} <span aria-hidden="true" className="transition group-hover:translate-x-1">→</span></span>
                </div>
              </Link>
            ))}
          </div>
        </div>
      </section>

      <section id="gallery" data-testid="home-gallery-section" className="home-section-ivory deferred-section scroll-mt-24 border-y border-[#0F2A43]/8 px-4 py-14 sm:px-6 md:py-20 lg:py-24">
        <div className="mx-auto max-w-[1400px]">
          <div className="mb-9 grid gap-7 border-b border-[#0F2A43]/12 pb-8 lg:mb-12 lg:grid-cols-[1fr_0.55fr] lg:items-end lg:pb-10">
            <div className="max-w-4xl">
              <div className="mb-5 flex items-center gap-3">
                <span aria-hidden="true" className="h-px w-10 bg-[#B8944F]" />
                <p className="text-xs font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Không gian khách sạn", "Inside Luxury Hotel")}</p>
              </div>
              <h2 className="font-serif text-4xl font-bold leading-[1.08] text-[#0F2A43] md:text-5xl lg:text-[3.5rem]">{localize("Những khoảng nghỉ được thiết kế để bạn chậm lại.", "Spaces designed to help you slow down.")}</h2>
            </div>
            <div className="grid gap-5 lg:justify-items-end">
              <p className="max-w-xl text-sm font-medium leading-7 text-[#66727C]">{localize("Khám phá phòng nghỉ, tiện nghi và các không gian chung trước khi chọn hạng phòng phù hợp.", "Explore rooms, facilities, and shared spaces before choosing the stay that fits you.")}</p>
              <div className="flex w-full items-center justify-between gap-4 lg:w-auto lg:justify-end">
                <span className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F] md:hidden">{localize("Vuốt để xem thêm", "Swipe to explore")}</span>
                {galleryImages.length > 0 && (
                  <span data-testid="home-gallery-count" aria-live="polite" className="inline-flex min-h-11 items-center rounded-full border border-[#0F2A43]/12 bg-[#FBFAF6] px-4 text-xs font-bold tabular-nums text-[#66727C]">
                    <span className="mr-1 text-[#0F2A43]">{String(galleryStart + 1).padStart(2, "0")}</span> / {String(galleryImages.length).padStart(2, "0")}
                  </span>
                )}
              </div>
            </div>
          </div>

          <div data-testid="home-gallery-frame" className="relative md:px-12 lg:px-14">
            <div
              data-testid="home-gallery-mobile-scroller"
              onScroll={handleGalleryScroll}
              role="region"
              tabIndex={0}
              aria-label={localize("Thư viện ảnh, vuốt sang trái hoặc phải để xem thêm", "Photo gallery, swipe left or right to see more")}
              className="guest-horizontal-scroller flex snap-x snap-mandatory overflow-x-auto overscroll-x-contain scroll-smooth touch-pan-x rounded-[1.5rem] outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-4 motion-reduce:scroll-auto md:hidden"
            >
              {galleryImages.map((image, index) => {
                const src = image.imageUrl || image.image;
                if (!src) return null;
                return (
                  <figure data-gallery-slide key={`${src}-${index}`} className="group relative aspect-[4/3] w-full shrink-0 snap-center overflow-hidden rounded-[1.5rem] border border-[#0F2A43]/10 bg-[#E5E9ED] shadow-[0_20px_55px_rgba(15,42,67,0.13)]">
                    <ProgressiveImage src={src} alt={localize(image.title, image.titleEn) || localize("Không gian khách sạn", "Hotel interior")} fill sizes="100vw" className="object-cover" />
                    <div className="absolute inset-0 bg-gradient-to-t from-[#091E30]/82 via-[#0F2A43]/5 to-transparent" />
                    <figcaption className="absolute inset-x-0 bottom-0 flex items-end gap-3 p-5 text-white">
                      <span className="text-[10px] font-bold tabular-nums tracking-[0.16em] text-[#E4C77F]">{String(index + 1).padStart(2, "0")}</span>
                      <span className="text-sm font-semibold">{localize(image.title, image.titleEn) || localize("Không gian tại khách sạn", "A space at Luxury Hotel")}</span>
                    </figcaption>
                  </figure>
                );
              })}
              {!galleryImages.length && (
                <div className="flex aspect-[4/3] w-full shrink-0 items-center justify-center rounded-[1.5rem] border border-dashed border-[#0F2A43]/15 bg-[#F1F0EA] px-6 text-center text-sm font-semibold text-[#66727C]">
                  {localize("Chưa có ảnh trong thư viện.", "No gallery images are available yet.")}
                </div>
              )}
            </div>

            <div className="hidden gap-5 md:grid md:grid-cols-12 md:grid-rows-2" aria-live="polite">
              {visibleGalleryImages.map((image, index) => {
                const src = image.imageUrl || image.image;
                if (!src) return null;
                const itemIndex = galleryImages.length ? (galleryStart + index) % galleryImages.length : index;
                const layoutClass = index === 0
                  ? "md:col-span-7 md:row-span-2 md:min-h-[36rem] lg:col-span-8 lg:min-h-[39rem]"
                  : "md:col-span-5 md:min-h-[17.5rem] lg:col-span-4 lg:min-h-[19rem]";
                return (
                  <figure key={`${galleryStart}-${src}-${index}`} style={{ animationDelay: `${index * 85}ms` }} className={`home-carousel-card ${galleryDirection === "next" ? "home-carousel-card-next" : "home-carousel-card-previous"} group relative min-h-[19rem] overflow-hidden border border-[#0F2A43]/10 bg-[#E5E9ED] shadow-[0_20px_55px_rgba(15,42,67,0.12)] ${index === 0 ? "rounded-[2rem]" : "rounded-[1.5rem]"} ${layoutClass}`}>
                    <ProgressiveImage src={src} alt={localize(image.title, image.titleEn) || localize("Không gian khách sạn", "Hotel interior")} fill sizes={index === 0 ? "(min-width: 768px) 58vw, 100vw" : "(min-width: 768px) 42vw, 100vw"} className="object-cover group-hover:scale-[1.035]" />
                    <div className="absolute inset-0 bg-gradient-to-t from-[#091E30]/84 via-[#0F2A43]/4 to-transparent" />
                    <figcaption className={`absolute inset-x-0 bottom-0 flex items-end gap-3 text-white ${index === 0 ? "p-7 lg:p-8" : "p-5 lg:p-6"}`}>
                      <span className="mb-0.5 text-[10px] font-bold tabular-nums tracking-[0.16em] text-[#E4C77F]">{String(itemIndex + 1).padStart(2, "0")}</span>
                      <span className={index === 0 ? "font-serif text-xl font-bold lg:text-2xl" : "text-sm font-semibold lg:text-base"}>{localize(image.title, image.titleEn) || localize(index === 0 ? "Không gian nghỉ dưỡng" : "Một góc tại khách sạn", index === 0 ? "A restful setting" : "A corner of Luxury Hotel")}</span>
                    </figcaption>
                  </figure>
                );
              })}
              {!galleryImages.length && (
                <div className="flex min-h-[24rem] items-center justify-center rounded-[1.75rem] border border-dashed border-[#0F2A43]/15 bg-[#F1F0EA] text-sm font-semibold text-[#66727C] md:col-span-12">
                  {localize("Chưa có ảnh trong thư viện.", "No gallery images are available yet.")}
                </div>
              )}
            </div>
            {galleryImages.length > 3 && <CarouselControls testIdPrefix="home-gallery" previousLabel={localize("Ảnh trước", "Previous images")} nextLabel={localize("Ảnh tiếp theo", "Next images")} onPrevious={() => moveGallery("previous")} onNext={() => moveGallery("next")} />}
          </div>
        </div>
      </section>

      <section className="home-section-navy-soft deferred-section mx-auto max-w-[1400px] rounded-[2rem] px-6 pb-16 pt-8 md:px-10 md:pb-20 md:pt-10">
        <div className="mb-10 flex flex-col justify-between gap-4 md:flex-row md:items-end">
          <div>
            <p className="mb-4 text-xs font-bold uppercase tracking-[0.25em] text-[#80632F]">{localize("Tiện nghi nổi bật", "Our best facilities")}</p>
            <h2 className="max-w-3xl font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">{localize("Mọi dịch vụ cần thiết đều rõ ràng ngay từ cái nhìn đầu tiên.", "Services guests can understand at a glance.")}</h2>
          </div>
          <div className="flex flex-wrap items-center gap-4 md:justify-end">
            <Link href="/facilities" className="text-sm font-bold uppercase tracking-[0.18em] text-[#80632F] hover:underline">{localize("Xem tất cả tiện nghi", "View all facilities")}</Link>
          </div>
        </div>
        <div data-testid="home-facilities-frame" className="relative md:px-12">
          <div ref={facilityScrollerRef} tabIndex={0} aria-label={localize("Danh sách tiện nghi nổi bật", "Featured facilities carousel")} className="guest-horizontal-scroller scroll-smooth overflow-x-auto pb-5 outline-none">
            <div className="motion-stagger flex snap-x snap-mandatory items-stretch gap-6">
            {visibleFacilities.map((facility, index) => {
              const name = localize(facility.facilityName || facility.name, facility.facilityNameEn) || localize("Tiện nghi khách sạn", "Hotel facility");
              const image = facility.imageUrl || facility.image || facility.icon;
              const description = localize(facility.description, facility.descriptionEn) || localize("Thông tin tiện nghi đang được cập nhật.", "Facility information is being updated.");
              if (!image) return null;
              return (
                <button
                  key={`${name}-${index}`}
                  type="button"
                  aria-haspopup="dialog"
                  aria-label={localize(`Xem chi tiết ${name}`, `View details for ${name}`)}
                  onClick={() => setSelectedFacility(facility)}
                  className="group relative h-[78vw] w-[78vw] shrink-0 snap-start overflow-hidden rounded-[1.75rem] border border-[#0F2A43]/10 bg-[#E5E9ED] text-left shadow-sm transition duration-300 hover:-translate-y-1 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-4 sm:h-[21rem] sm:w-[21rem] lg:h-[23rem] lg:w-[23rem]"
                >
                  <ProgressiveImage src={image} fallbackSrc={HOME_CONTENT.hero.bg} alt={name} fill loading={index < 4 ? "eager" : "lazy"} sizes="(min-width: 1024px) 23rem, (min-width: 640px) 21rem, 78vw" className="object-cover group-hover:scale-105" />
                  <div className="absolute inset-0 bg-gradient-to-t from-[#0F2A43]/88 via-[#0F2A43]/22 to-transparent" />
                  <div className="absolute inset-x-0 bottom-0 flex min-h-[11.5rem] flex-col justify-end p-5 text-white">
                    <div className="flex items-start justify-between gap-3">
                      <h3 className="font-serif text-2xl font-bold leading-tight">{name}</h3>
                      <span className="mt-1 text-xl text-[#C8A35B] transition group-hover:translate-x-1">→</span>
                    </div>
                    <p className="mt-3 line-clamp-3 text-sm font-medium leading-6 text-white/78">{description}</p>
                    <span className="mt-4 text-xs font-bold uppercase tracking-[0.16em] text-[#C8A35B]">{localize("Xem tiện nghi", "View facility")}</span>
                  </div>
                </button>
              );
            })}
            {!visibleFacilities.length && (
              <div className="flex h-[23rem] w-[78vw] shrink-0 items-center justify-center rounded-[1.75rem] border border-dashed border-[#0F2A43]/15 bg-[#FBFAF6] px-6 text-center text-sm font-semibold text-[#66727C] sm:w-[21rem] lg:w-[23rem]">
                {localize("Chưa có tiện nghi để hiển thị.", "No facilities are available yet.")}
              </div>
            )}
            </div>
          </div>
          {visibleFacilities.length > 1 && <CarouselControls testIdPrefix="home-facilities" previousLabel={localize("Tiện nghi trước", "Previous facilities")} nextLabel={localize("Tiện nghi tiếp theo", "Next facilities")} onPrevious={() => scrollFacilities("previous")} onNext={() => scrollFacilities("next")} />}
        </div>
      </section>

      <section id="booking-guide" className="home-section-gold-soft deferred-section scroll-mt-24 py-16 text-[#0F2A43] md:py-20">
        <div className="mx-auto grid max-w-7xl gap-12 px-6 md:px-10 lg:grid-cols-[0.78fr_1.22fr] lg:items-start">
          <div className="lg:sticky lg:top-28">
            <p className="mb-4 text-xs font-bold uppercase tracking-[0.24em] text-[#80632F]">{localize("Hành trình đặt phòng", "Booking journey")}</p>
            <h2 className="font-serif text-4xl font-bold leading-tight md:text-5xl">{localize("Mỗi bước đều có trạng thái rõ ràng.", "Every step has a clear status.")}</h2>
            <p className="mt-5 max-w-xl text-sm font-medium leading-7 text-[#66727C]">
              {localize("Từ lúc chọn nhiều hạng phòng đến khi hoàn tất thanh toán cuối, bạn luôn biết số tiền cần trả, trạng thái đơn và hành động tiếp theo.", "From selecting multiple room types to final payment, you can always see the amount due, booking status, and next action.")}
            </p>

            <div className="mt-8 grid gap-3 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
              <div className="border-l-2 border-[#80632F] bg-[#FBFAF6] p-4">
                <p className="font-serif text-2xl font-bold text-[#0F2A43]">50%</p>
                <p className="mt-1 text-xs font-semibold text-[#66727C]">{localize("Đặt cọc để giữ chỗ", "Deposit to reserve")}</p>
              </div>
              <div className="border-l-2 border-[#0F2A43] bg-[#FBFAF6] p-4">
                <p className="font-serif text-2xl font-bold text-[#0F2A43]">100%</p>
                <p className="mt-1 text-xs font-semibold text-[#66727C]">{localize("Thanh toán toàn bộ", "Pay in full")}</p>
              </div>
            </div>

            <div className="mt-8 flex flex-wrap gap-3">
              <Link href="/reservation" className="inline-flex min-h-12 items-center rounded-lg bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#091E30]">
                {localize("Bắt đầu đặt phòng", "Start reservation")}
              </Link>
              <Link href="/my-bookings" className="inline-flex min-h-12 items-center rounded-lg border border-[#0F2A43]/18 bg-[#FBFAF6] px-6 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F]">
                {localize("Tra cứu đơn", "Find a booking")}
              </Link>
            </div>
          </div>

          <ol className="border-y border-[#0F2A43]/12">
            {(locale === "vi" ? [
              ["01", "Kiểm tra tồn phòng thật", "Chọn ngày giờ, số khách, nhiều loại phòng và số lượng của từng loại trong cùng một đơn."],
              ["02", "Chọn mức thanh toán", "Chọn đặt cọc 50% hoặc trả 100%, sau đó quét đúng mã QR trong thời gian hiệu lực."],
              ["03", "Theo dõi xác nhận", "Thời gian từ ngân hàng quyết định giao dịch đúng hạn; nhân viên xác nhận đơn và gán đúng phòng khi check-in."],
              ["04", "Hoàn tất kỳ lưu trú", "Thanh toán phần còn lại, theo dõi hoàn tiền nếu có và chỉ checkout khi nghĩa vụ đã được đối soát."],
            ] : [
              ["01", "Check live inventory", "Choose dates, guests, multiple room types, and the quantity required for each type in one booking."],
              ["02", "Choose how much to pay", "Select a 50% deposit or 100% payment, then scan the exact QR code before it expires."],
              ["03", "Follow confirmation", "The bank provider time determines punctuality; staff confirm the booking and assign matching rooms at check-in."],
              ["04", "Complete the stay", "Pay the remaining balance, follow any refund, and checkout only after every obligation is reconciled."],
            ]).map(([step, title, detail]) => (
              <li key={step} className="grid gap-4 border-b border-[#0F2A43]/12 py-7 last:border-b-0 sm:grid-cols-[4rem_1fr_auto] sm:items-start">
                <span className="font-serif text-3xl font-semibold tabular-nums text-[#80632F]">{step}</span>
                <div>
                  <h3 className="text-lg font-bold text-[#0F2A43]">{title}</h3>
                  <p className="mt-2 max-w-2xl text-sm leading-7 text-[#66727C]">{detail}</p>
                </div>
                <span aria-hidden="true" className="hidden pt-1 text-xl text-[#80632F] sm:block">↗</span>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="home-section-surface deferred-section mx-auto max-w-7xl px-6 py-16 md:px-10 md:py-20">
        <div className="mb-10 flex flex-col justify-between gap-4 md:flex-row md:items-end">
          <div>
            <h2 className="font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">{localize("Lựa chọn phòng dành cho bạn", "Room choices for you")}</h2>
          </div>
          <Link href="/rooms" className="text-sm font-bold uppercase tracking-[0.18em] text-[#80632F] hover:underline">
            {localize("Xem tất cả phòng", "View all rooms")}
          </Link>
        </div>

        <div className="motion-stagger grid gap-10">
          {favoriteRooms.map((room, index) => {
            const title = localize(room.typeName || room.title, room.typeNameEn) || localize("Phòng nghỉ cao cấp", "Luxury room");
            const image = room.imageUrl || room.image;
            return (
              <article key={`${title}-${index}`} className="grid gap-6 md:grid-cols-[4rem_22rem_1fr] md:items-center">
                <span className="hidden font-serif text-5xl text-[#0F2A43]/70 md:block">{String(index + 1).padStart(2, "0")}</span>
                <Link href={room.id ? `/rooms/${room.id}` : "/rooms"} className="relative aspect-[8/6] overflow-hidden rounded-[2rem] bg-[#E5E9ED]">
                  {image ? (
                    <ProgressiveImage src={image} alt={title} fill sizes="(min-width: 768px) 22rem, 100vw" className="object-cover hover:scale-105" />
                  ) : (
                    <div className="flex h-full w-full items-center justify-center text-sm font-semibold text-[#66727C]">{localize("Chưa có ảnh", "No image")}</div>
                  )}
                </Link>
                <div className="space-y-4">
                  <p className="text-sm font-bold uppercase tracking-[0.18em] text-[#66727C]">{typeof room.price === "number" ? room.price.toLocaleString(localeTag, { style: "currency", currency: "VND", maximumFractionDigits: 0 }) : room.price || localize("Liên hệ", "Contact us")} / {localize("giờ", "hour")}</p>
                  <h3 className="font-serif text-3xl font-bold text-[#0F2A43] md:text-5xl">{title}</h3>
                  <p className="max-w-2xl leading-7 text-[#66727C]">
                    {localize(room.description, room.descriptionEn) || localize("Không gian nghỉ dưỡng yên tĩnh, tiện nghi và sẵn sàng phục vụ từ khi nhận đến lúc trả phòng.", "A quiet stay with generous space, essential amenities, and service ready from arrival to checkout.")}
                  </p>
                  <Link href={room.id ? `/rooms/${room.id}` : "/rooms"} className="inline-flex items-center gap-2 text-sm font-bold uppercase tracking-[0.16em] text-[#80632F]">
                    {localize("Xem chi tiết", "Learn more")} <span>→</span>
                  </Link>
                </div>
              </article>
            );
          })}
          {!favoriteRooms.length && (
            <div className="rounded-[1.75rem] border border-dashed border-[#0F2A43]/15 bg-[#FBFAF6] p-8 text-sm font-semibold text-[#66727C]">
              {localize("Chưa có loại phòng để hiển thị.", "No room types are available yet.")}
            </div>
          )}
        </div>
      </section>

      <section className="home-section-navy-soft deferred-section py-14 md:py-16" aria-labelledby="plan-stay-title">
        <div className="mx-auto grid max-w-7xl gap-8 px-6 md:px-10 lg:grid-cols-[0.72fr_1.28fr] lg:items-center">
          <div>
            <p className="text-sm font-semibold text-[#80632F]">{localize("Trước ngày nhận phòng", "Before arrival")}</p>
            <h2 id="plan-stay-title" className="mt-3 font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">
              {localize("Chuẩn bị kỳ nghỉ trong một nơi.", "Plan the stay in one place.")}
            </h2>
            <p className="mt-4 max-w-xl text-sm leading-7 text-[#66727C]">
              {localize("Tra cứu thanh toán, xem lại đơn hoặc gửi yêu cầu cho lễ tân mà không phải bắt đầu lại quy trình đặt phòng.", "Review payments, reopen a booking, or message the front desk without restarting the reservation flow.")}
            </p>
          </div>
          <div className="grid gap-3 sm:grid-cols-3">
            {[
              ["/my-bookings", localize("Tra cứu đơn", "Find a booking"), localize("Lịch sử và trạng thái thanh toán", "History and payment status")],
              ["/my-bookings", localize("Đơn của tôi", "My bookings"), localize("Lịch sử và hành động tiếp theo", "History and next actions")],
              ["/contact", localize("Nhờ lễ tân hỗ trợ", "Ask the front desk"), localize("Ngày lưu trú và yêu cầu cụ thể", "Dates and specific requests")],
            ].map(([href, title, detail]) => (
              <Link key={href} href={href} className="home-section-surface group min-h-36 rounded-2xl border border-[#0F2A43]/12 p-5 transition hover:-translate-y-1 hover:border-[#B8944F] hover:bg-[#FBFAF6]">
                <span className="text-sm font-bold text-[#0F2A43]">{title}</span>
                <span className="mt-3 block text-xs leading-6 text-[#66727C]">{detail}</span>
                <span aria-hidden="true" className="mt-5 block text-lg text-[#80632F] transition group-hover:translate-x-1">→</span>
              </Link>
            ))}
          </div>
        </div>
      </section>

      <section className="home-section-ivory deferred-section hotel-paper-pattern border-y border-[#0F2A43]/8 py-16 md:py-20">
        <div className="mx-auto grid max-w-7xl gap-12 px-6 md:px-10 lg:grid-cols-[0.65fr_1.35fr]">
          <div>
            <p className="mb-4 text-xs font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Trước khi đặt", "Before you book")}</p>
            <h2 className="font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">{localize("Những điều quan trọng, trả lời ngắn gọn.", "The important details, answered clearly.")}</h2>
            <p className="mt-5 text-sm leading-7 text-[#66727C]">
              {localize("Không tìm thấy câu trả lời? Gửi yêu cầu cho lễ tân, kèm ngày lưu trú và số khách để được tư vấn chính xác.", "Need more detail? Send the front desk your dates and guest count for an exact recommendation.")}
            </p>
            <Link href="/contact" className="mt-7 inline-flex min-h-11 items-center gap-2 text-sm font-bold text-[#0F2A43] transition hover:text-[#80632F]">
              {localize("Liên hệ lễ tân", "Contact the front desk")} <span aria-hidden="true">→</span>
            </Link>
          </div>

          <dl className="grid gap-x-8 gap-y-0 md:grid-cols-2">
            {(locale === "vi" ? [
              ["Một đơn có thể đặt nhiều phòng không?", "Có. Bạn có thể chọn nhiều loại phòng và số lượng từng loại; khi check-in, nhân viên gán đúng số phòng thuộc loại đã đặt."],
              ["Có thể chọn cọc hoặc trả đủ không?", "Có. Bước thanh toán cho phép chọn cọc 50% hoặc thanh toán 100% qua QR."],
              ["Chuyển khoản sát giờ hết hạn thì sao?", "Hệ thống dùng thời gian giao dịch do ngân hàng cung cấp để xác định đúng hạn, không chỉ dựa vào lúc webhook đến máy chủ."],
              ["Chuyển thiếu hoặc chuyển thừa được xử lý thế nào?", "Chuyển thiếu không hoàn tất đặt phòng. Chuyển thừa được ghi nhận đầy đủ và tạo nghĩa vụ hoàn phần dư để nhân viên xử lý."],
              ["Tôi theo dõi thanh toán ở đâu?", "Dùng trang Tra cứu đơn hoặc Đơn của tôi để xem trạng thái đặt phòng, thanh toán, hoàn tiền và bước tiếp theo."],
              ["Hoàn tiền được xác nhận thế nào?", "Hoàn qua QR được ngân hàng tự đối chiếu theo mã và đúng số tiền; ảnh biên lai chỉ dùng tùy chọn khi fallback. Hoàn tiền mặt được nhân viên xác nhận sau khi giao trực tiếp tại quầy."],
            ] : [
              ["Can one booking include several rooms?", "Yes. Select multiple room types and quantities; at check-in, staff assign the required number of rooms matching each booked type."],
              ["Can I choose a deposit or full payment?", "Yes. The payment step supports a 50% deposit or a 100% payment by QR."],
              ["What if I transfer near the QR deadline?", "The bank provider timestamp determines whether payment was on time, rather than only the time the server received the webhook."],
              ["What happens to underpayments or overpayments?", "An underpayment does not complete the booking. An overpayment is fully recorded and creates a refund obligation for the surplus."],
              ["Where can I follow payment status?", "Use Find a booking or My bookings to see booking, payment, refund, and next-step status."],
              ["How is a refund confirmed?", "A QR refund is automatically reconciled by its code and exact amount; a receipt is optional only for fallback. Cash refunds are confirmed after the handover at the front desk."],
            ]).map(([question, answer]) => (
              <div key={question} className="border-t border-[#0F2A43]/14 py-6 first:border-t-0 md:[&:nth-child(2)]:border-t-0">
                <dt className="text-base font-bold leading-6 text-[#0F2A43]">{question}</dt>
                <dd className="mt-3 text-sm leading-7 text-[#66727C]">{answer}</dd>
              </div>
            ))}
          </dl>
        </div>
      </section>

      <FacilityDetailModal facility={selectedFacility} onClose={() => setSelectedFacility(null)} />
      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
