"use client";

import React, { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { FACILITIES_CONTENT } from "@/constants/content";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { getPublicFacilities } from "@/lib/public-catalog";
import GuestPageHero from "@/components/guest/GuestPageHero";
import FacilityDetailModal, { type FacilityDetailItem } from "@/components/guest/FacilityDetailModal";

type FacilityItem = FacilityDetailItem;

type FacilityFilter = "ALL" | "PUBLIC" | "ROOM";

export default function FacilitiesPage() {
  const { locale, localize } = useLanguage();
  const [facilities, setFacilities] = useState<FacilityItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [activeFilter, setActiveFilter] = useState<FacilityFilter>("ALL");
  const [selectedFacility, setSelectedFacility] = useState<FacilityItem | null>(null);
  const highlights = locale === "vi" ? [
    { title: "Thư giãn & phục hồi", detail: "Hồ bơi và spa theo lịch hẹn giúp bạn chủ động sắp xếp thời gian nghỉ." },
    { title: "Vận động mỗi ngày", detail: "Không gian thể hình hiện đại phục vụ khách trong suốt kỳ lưu trú." },
    { title: "Không gian dùng chung", detail: "Thư viện, quán cà phê và khu mua sắm cho từng nhịp nghỉ khác nhau." },
  ] : [
    { title: "Rest & recovery", detail: "Pool and scheduled spa services help guests plan time to recharge." },
    { title: "Move every day", detail: "A modern fitness space is available throughout the stay." },
    { title: "Shared spaces", detail: "Library, cafe, and marketplace spaces support different rhythms of travel." },
  ];

  const visibleFacilities = useMemo(() => facilities
    .filter((facility) => {
      const image = facility.imageUrls?.[0] || facility.imageUrl || facility.icon || facility.image;
      return image && image !== "wifi-icon";
    })
    .sort((left, right) => {
      const priority = (facility: FacilityItem) => /spa|fitness|thể hình/i.test(`${facility.facilityName || ""} ${facility.facilityNameEn || ""}`) ? 0 : 1;
      return priority(left) - priority(right);
    }), [facilities]);

  const filteredFacilities = useMemo(
    () => activeFilter === "ALL"
      ? visibleFacilities
      : visibleFacilities.filter((facility) => facility.type?.toUpperCase() === activeFilter),
    [activeFilter, visibleFacilities],
  );

  const filters: Array<{ value: FacilityFilter; label: string }> = [
    { value: "ALL", label: localize("Tất cả", "All") },
    { value: "PUBLIC", label: localize("Không gian chung", "Shared spaces") },
    { value: "ROOM", label: localize("Trong phòng", "In-room") },
  ];

  useEffect(() => {
    getPublicFacilities<FacilityItem>()
      .then(setFacilities)
      .catch(err => {
        console.error("Error fetching facilities:", err);
        setFacilities([]);
      })
      .finally(() => {
        setIsLoading(false);
      });
  }, []);

  return (
    <div className="bg-[#F1F0EA] text-[#0F2A43]">
      <GuestPageHero
        imageSrc={FACILITIES_CONTENT.hero.bg}
        imageAlt={localize("Hồ bơi và không gian thư giãn tại Luxury Hotel", "Pool and relaxation spaces at Luxury Hotel")}
        eyebrow={localize("Tiện nghi", "Facilities")}
        title={localize("Không gian nâng tầm kỳ nghỉ.", "Spaces designed to elevate your stay.")}
        description={localize("Từ hồ bơi, bữa sáng đến những góc yên tĩnh, bạn có thể hình dung đầy đủ trải nghiệm khách sạn trước khi đặt phòng.", "From the pool to breakfast and quiet corners, guests can understand the hotel experience before they book.")}
        actions={(
          <>
            <Link href="/reservation" className="inline-flex min-h-12 items-center rounded-xl bg-[#D8C398] px-6 text-sm font-bold text-[#091E30] transition hover:bg-[#C8A35B] active:translate-y-px">
              {localize("Đặt phòng", "Book a room")}
            </Link>
            <Link href="/rooms" className="inline-flex min-h-12 items-center rounded-xl border border-white/35 bg-white/8 px-6 text-sm font-bold text-white transition hover:bg-white/14 active:translate-y-px">
              {localize("Xem hạng phòng", "View room types")}
            </Link>
          </>
        )}
      />

      {/* Introduction Section */}
      <section id="explore" className="deferred-section mx-auto grid max-w-7xl gap-10 px-6 py-24 md:grid-cols-[0.9fr_1.1fr] md:px-10 md:items-end">
        <div>
        <p className="mb-4 text-xs font-bold uppercase tracking-[0.25em] text-[#80632F]">{localize("Dịch vụ dành cho khách", "Guest services")}</p>
        <h2 className="font-serif text-4xl md:text-5xl font-bold text-primary-navy mb-6">
          {localize("TIỆN NGHI", FACILITIES_CONTENT.hero.title)}
        </h2>
        <p className="text-text-dark text-sm md:text-base leading-loose max-w-xl">
          {localize("Chúng tôi chú trọng từng nhu cầu để kỳ nghỉ của bạn thật trọn vẹn, từ không gian thư giãn đến các dịch vụ thiết yếu trong khuôn viên.", FACILITIES_CONTENT.hero.subtitle)}
        </p>
        </div>
        <div className="motion-stagger grid gap-3 sm:grid-cols-3">
          {highlights.map((item) => (
            <div key={item.title} className="rounded-[1.25rem] bg-[#FBFAF6] p-5 shadow-sm even:bg-[#EAE2D2]">
              <h3 className="font-serif text-xl font-bold text-[#0F2A43]">{item.title}</h3>
              <p className="mt-3 text-sm font-medium leading-6 text-[#66727C]">{item.detail}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Facilities Grid */}
      <section className="deferred-section max-w-7xl mx-auto px-6 pb-24 md:px-10">
        <div className="mb-8 flex flex-col gap-4 border-y border-[#0F2A43]/12 py-5 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Khám phá theo nhu cầu", "Browse by need")}</p>
            <p className="mt-1 text-sm font-semibold text-[#66727C]" aria-live="polite">
              {localize(`${filteredFacilities.length} tiện nghi phù hợp`, `${filteredFacilities.length} matching facilities`)}
            </p>
          </div>
          <div className="flex flex-wrap gap-2" role="group" aria-label={localize("Lọc tiện nghi", "Filter facilities")}>
            {filters.map((filter) => (
              <button
                key={filter.value}
                type="button"
                aria-pressed={activeFilter === filter.value}
                onClick={() => setActiveFilter(filter.value)}
                className={`min-h-10 rounded-full border px-4 text-xs font-bold transition ${activeFilter === filter.value ? "border-[#0F2A43] bg-[#0F2A43] text-white" : "border-[#0F2A43]/16 bg-[#FBFAF6] text-[#0F2A43] hover:border-[#B8944F] hover:bg-[#EAE2D2]"}`}
              >
                {filter.label}
              </button>
            ))}
          </div>
        </div>
        {isLoading ? (
          <div className="grid gap-6 md:grid-cols-3">
            {[0, 1, 2].map((item) => (
              <div key={item} className="skeleton-surface h-80 rounded-[1.5rem]" />
            ))}
          </div>
        ) : (
        <div className="motion-stagger grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
        {filteredFacilities.map((facility, index) => {
          const name = localize(facility.facilityName || facility.name, facility.facilityNameEn);
          const image = facility.imageUrls?.[0] || facility.imageUrl || facility.icon || facility.image;
          // Skip utility/service facilities like FREE WIFI from rendering on facilities list if they don't have custom image
          if (image === 'wifi-icon' || !image) return null;
          
          return (
            <button
              key={facility.id || `${name}-${index}`}
              type="button"
              aria-haspopup="dialog"
              aria-label={localize(`Xem chi tiết ${name}`, `View details for ${name}`)}
              onClick={() => setSelectedFacility(facility)}
              className={`guest-media-lift group flex h-full min-h-[27rem] flex-col overflow-hidden rounded-[1.75rem] text-left shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-4 ${index % 2 === 0 ? "bg-[#FBFAF6]" : "bg-[#EAE2D2]"}`}
            >
              <div className="relative aspect-square w-full shrink-0 overflow-hidden bg-[#E5E9ED]">
              <ProgressiveImage
                src={image}
                fallbackSrc={FACILITIES_CONTENT.hero.bg}
                alt={name}
                fill
                loading={index < 6 ? "eager" : "lazy"}
                fetchPriority={index < 2 ? "high" : "auto"}
                sizes="(min-width: 1024px) 33vw, (min-width: 640px) 50vw, 100vw"
                className="object-cover group-hover:scale-105"
              />
              </div>
              <div className="flex min-h-[9.5rem] flex-1 items-center justify-between gap-4 p-5">
                <div>
                <p className="mb-1 text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">
                  {facility.type?.toUpperCase() === "ROOM" ? localize("Tiện nghi trong phòng", "In-room facility") : localize("Tiện nghi chung", "Shared facility")}
                </p>
                <h3 className="font-serif text-2xl font-bold text-primary-navy">
                  {name}
                </h3>
                <p className="mt-2 line-clamp-2 text-sm font-medium leading-6 text-[#66727C]">{localize(facility.description, facility.descriptionEn) || localize("Thông tin đang được cập nhật.", "Information is being updated.")}</p>
                </div>
                <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-[#0F2A43]/14 bg-white/70 text-xl text-[#80632F] transition group-hover:translate-x-1 group-hover:border-[#B8944F] group-hover:bg-white" aria-hidden="true">
                  →
                </span>
              </div>
            </button>
          );
        })}
        {!filteredFacilities.length && (
          <div className="col-span-full rounded-[1.75rem] border border-dashed border-[#0F2A43]/15 bg-[#FBFAF6] p-10 text-center">
            <h3 className="font-serif text-2xl font-bold text-primary-navy">{localize("Chưa có tiện nghi phù hợp", "No matching facilities")}</h3>
            <p className="mt-3 text-sm font-semibold text-[#66727C]">{localize("Chọn nhóm khác hoặc bổ sung tiện nghi trong Dashboard.", "Choose another group or add facilities in the Dashboard.")}</p>
          </div>
        )}
        </div>
        )}
      </section>

      <section className="deferred-section mx-auto max-w-7xl px-6 pb-28 md:px-10">
        <div className="grid overflow-hidden rounded-[2rem] bg-[#0F2A43] text-white md:grid-cols-[1fr_0.85fr]">
          <div className="p-8 md:p-12">
            <p className="text-xs font-bold uppercase tracking-[0.28em] text-[#C8A35B]">{localize("Lên kế hoạch lưu trú", "Plan the stay")}</p>
            <h2 className="mt-4 font-serif text-4xl font-bold md:text-5xl">{localize("Chọn tiện nghi phù hợp, sau đó chọn phòng.", "Choose the right facilities, then find your room.")}</h2>
            <p className="mt-5 max-w-xl text-sm font-medium leading-7 text-white/70">
              {localize("Thông tin tiện nghi giúp bạn lựa chọn hạng phòng phù hợp thay vì chỉ xem một danh sách hình ảnh.", "Facility details help guests choose the right room instead of serving as a static image list.")}
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <Link href="/rooms" className="rounded-[1.1rem] bg-[#C8A35B] px-6 py-3 text-xs font-bold uppercase tracking-[0.18em] text-[#0F2A43] transition hover:bg-[#d4aa62]">
                {localize("Xem phòng", "View rooms")}
              </Link>
              <Link href="/my-bookings" className="rounded-[1.1rem] border border-white/20 px-6 py-3 text-xs font-bold uppercase tracking-[0.18em] text-white transition hover:bg-white/10">
                {localize("Đơn của tôi", "My bookings")}
              </Link>
            </div>
          </div>
          <div className="relative min-h-72 overflow-hidden bg-[#091E30]">
            <ProgressiveImage
              src={visibleFacilities[0]?.imageUrl || visibleFacilities[0]?.image || FACILITIES_CONTENT.hero.bg}
              fallbackSrc={FACILITIES_CONTENT.hero.bg}
              alt={localize("Không gian tiện nghi khách sạn", "Hotel facility space")}
              fill
              sizes="(min-width: 768px) 42vw, 100vw"
              className="object-cover"
            />
          </div>
        </div>
      </section>

      <FacilityDetailModal facility={selectedFacility} onClose={() => setSelectedFacility(null)} />
    </div>
  );
}
