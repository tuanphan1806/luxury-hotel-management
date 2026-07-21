"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import { FACILITIES_CONTENT } from "@/constants/content";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { getPublicFacilities } from "@/lib/public-catalog";
import GuestPageHero from "@/components/guest/GuestPageHero";

interface FacilityItem {
  id?: number;
  facilityName?: string;
  facilityNameEn?: string;
  name?: string;
  description?: string;
  descriptionEn?: string;
  imageUrl?: string;
  icon?: string;
  image?: string;
}

export default function FacilitiesPage() {
  const { locale, localize } = useLanguage();
  const [facilities, setFacilities] = useState<FacilityItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const highlights = locale === "vi" ? [
    { title: "Hồ bơi và thư giãn", detail: "Không gian thư giãn với thời gian phục vụ được lễ tân quản lý." },
    { title: "Dịch vụ bữa sáng", detail: "Bữa sáng tiện lợi cho chuyến công tác và kỳ nghỉ gia đình." },
    { title: "Góc yên tĩnh", detail: "Không gian đọc sách, làm việc và chờ dành cho những phút thư thả." },
  ] : [
    { title: "Pool & wellness", detail: "Relaxation spaces with schedules managed by the front desk." },
    { title: "Breakfast service", detail: "A practical morning setup for business trips and family stays." },
    { title: "Quiet corners", detail: "Reading, work, and waiting spaces for slower moments." },
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
        eyebrow={localize("Tiện ích", "Facilities")}
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
          {localize("TIỆN ÍCH", FACILITIES_CONTENT.hero.title)}
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
        {isLoading ? (
          <div className="grid gap-6 md:grid-cols-3">
            {[0, 1, 2].map((item) => (
              <div key={item} className="skeleton-surface h-80 rounded-[1.5rem]" />
            ))}
          </div>
        ) : (
        <div className="motion-stagger grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
        {facilities.map((facility, index) => {
          const name = localize(facility.facilityName || facility.name, facility.facilityNameEn);
          const image = facility.imageUrl || facility.icon || facility.image;
          // Skip utility/service facilities like FREE WIFI from rendering on facilities list if they don't have custom image
          if (image === 'wifi-icon' || !image) return null;
          
          return (
            <article key={index} className={`group flex h-full min-h-[27rem] flex-col overflow-hidden rounded-[1.75rem] shadow-sm transition duration-300 hover:-translate-y-1 hover:shadow-xl ${index % 2 === 0 ? "bg-[#FBFAF6]" : "bg-[#EAE2D2]"}`}>
              <div className="relative aspect-square w-full shrink-0 overflow-hidden bg-[#E5E9ED]">
              <ProgressiveImage
                src={image}
                alt={name}
                fill
                sizes="(min-width: 1024px) 33vw, (min-width: 640px) 50vw, 100vw"
                className="object-cover group-hover:scale-105"
              />
              </div>
              <div className="flex min-h-[8.5rem] flex-1 items-center justify-between gap-4 p-5">
                <div>
                <p className="mb-1 text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Tiện ích", "Facility")}</p>
                <h3 className="font-serif text-2xl font-bold text-primary-navy">
                  {name}
                </h3>
                <p className="mt-2 line-clamp-2 text-sm font-medium leading-6 text-[#66727C]">{localize(facility.description, facility.descriptionEn) || localize("Thông tin đang được cập nhật.", "Information is being updated.")}</p>
                </div>
                <span className="text-2xl text-accent-gold transition-transform duration-300 group-hover:translate-x-1">→</span>
              </div>
            </article>
          );
        })}
        {!facilities.filter((facility) => {
          const image = facility.imageUrl || facility.icon || facility.image;
          return image && image !== "wifi-icon";
        }).length && (
          <div className="col-span-full rounded-[1.75rem] border border-dashed border-[#0F2A43]/15 bg-[#FBFAF6] p-10 text-center">
            <h3 className="font-serif text-2xl font-bold text-primary-navy">{localize("Chưa có tiện ích để hiển thị", "No facilities to display")}</h3>
            <p className="mt-3 text-sm font-semibold text-[#66727C]">{localize("Thêm tiện ích trong Dashboard để hiển thị tại đây.", "Add facilities in the Dashboard to display them here.")}</p>
          </div>
        )}
        </div>
        )}
      </section>

      <section className="deferred-section mx-auto max-w-7xl px-6 pb-28 md:px-10">
        <div className="grid overflow-hidden rounded-[2rem] bg-[#0F2A43] text-white md:grid-cols-[1fr_0.85fr]">
          <div className="p-8 md:p-12">
            <p className="text-xs font-bold uppercase tracking-[0.28em] text-[#C8A35B]">{localize("Lên kế hoạch lưu trú", "Plan the stay")}</p>
            <h2 className="mt-4 font-serif text-4xl font-bold md:text-5xl">{localize("Chọn tiện ích phù hợp, sau đó chọn phòng.", "Choose the right facilities, then find your room.")}</h2>
            <p className="mt-5 max-w-xl text-sm font-medium leading-7 text-white/70">
              {localize("Thông tin tiện ích giúp bạn lựa chọn loại phòng phù hợp thay vì chỉ là một danh sách hình ảnh.", "Facility details help guests choose the right room instead of serving as a static image list.")}
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
              src={facilities.find((item) => item.imageUrl || item.image)?.imageUrl || facilities.find((item) => item.imageUrl || item.image)?.image || FACILITIES_CONTENT.hero.bg}
              alt={localize("Không gian tiện ích khách sạn", "Hotel facility space")}
              fill
              sizes="(min-width: 768px) 42vw, 100vw"
              className="object-cover"
            />
          </div>
        </div>
      </section>
    </div>
  );
}
