"use client";

import React, { Suspense, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { ROOMS_CONTENT } from "@/constants/content";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { useFavorites } from "@/components/favorites/FavoritesProvider";
import { getPublicRoomTypes } from "@/lib/public-catalog";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import GuestPageHero from "@/components/guest/GuestPageHero";

interface RoomType {
  id: number;
  typeName: string;
  typeNameEn?: string;
  price: number;
  maxGuests: number;
  description: string;
  descriptionEn?: string;
  imageUrl: string;
  facilities?: Array<{ id?: number; facilityName: string; facilityNameEn?: string }>;
  averageRating?: number;
  totalReviews?: number;
}

function RoomsPageContent() {
  const { localeTag, localize } = useLanguage();
  const searchParams = useSearchParams();
  const { favoriteRoomIds, favoriteCount, isReady: favoritesReady, isFavorite, toggleFavorite } = useFavorites();
  const [roomTypes, setRoomTypes] = useState<RoomType[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState("");
  const [minimumGuests, setMinimumGuests] = useState("0");
  const [sortBy, setSortBy] = useState<"recommended" | "price-asc" | "price-desc" | "rating">("recommended");
  const favoritesOnly = searchParams.get("favorites") === "1";

  // Fetch Room Types
  useEffect(() => {
    getPublicRoomTypes<RoomType>()
      .then(setRoomTypes)
      .catch(err => {
        console.error("Error fetching room types:", err);
        setRoomTypes([]);
      })
      .finally(() => {
        setIsLoading(false);
      });
  }, []);

  const filteredRoomTypes = useMemo(() => {
    const normalizedSearch = searchTerm.trim().toLocaleLowerCase();
    const guestRequirement = Number(minimumGuests || 0);
    const filtered = roomTypes.filter((room) => {
      const searchableText = [
        room.typeName,
        room.typeNameEn,
        room.description,
        room.descriptionEn,
        ...(room.facilities || []).flatMap((facility) => [facility.facilityName, facility.facilityNameEn]),
      ].filter(Boolean).join(" ").toLocaleLowerCase();

      return (!normalizedSearch || searchableText.includes(normalizedSearch))
        && (!guestRequirement || Number(room.maxGuests || 0) >= guestRequirement)
        && (!favoritesOnly || favoriteRoomIds.includes(room.id));
    });

    return [...filtered].sort((left, right) => {
      if (sortBy === "price-asc") return Number(left.price || 0) - Number(right.price || 0);
      if (sortBy === "price-desc") return Number(right.price || 0) - Number(left.price || 0);
      if (sortBy === "rating") return Number(right.averageRating || 0) - Number(left.averageRating || 0);
      return 0;
    });
  }, [favoriteRoomIds, favoritesOnly, minimumGuests, roomTypes, searchTerm, sortBy]);

  const resetDiscovery = () => {
    setSearchTerm("");
    setMinimumGuests("0");
    setSortBy("recommended");
  };

  return (
    <div className="bg-white">
      <GuestPageHero
        imageSrc={ROOMS_CONTENT.hero.bg}
        imageAlt={localize("Phòng nghỉ tại Luxury Hotel", "A guest room at Luxury Hotel")}
        eyebrow={localize("Danh mục lưu trú", "Accommodation collection")}
        title={localize("Khám phá các loại phòng", "Explore our room types")}
        description={localize("Xem không gian, tiện nghi và đánh giá của từng loại phòng trước khi kiểm tra lịch trống.", "Review each room type's spaces, facilities and ratings before checking availability.")}
        contentPosition="left"
      />

      <div className="relative z-30 mx-auto -mt-12 flex max-w-5xl flex-col items-start justify-between gap-5 rounded-[1.5rem] border border-[#0F2A43]/10 bg-white p-6 shadow-xl md:flex-row md:items-center md:px-8">
        <div><p className="text-xs font-bold uppercase tracking-[0.16em] text-[#80632F]">{localize("Đã biết ngày lưu trú?", "Know your stay dates?")}</p><p className="mt-2 text-sm font-medium text-[#66727C]">{localize("Sang trang đặt phòng để kiểm tra chính xác số phòng còn trống và chọn nhiều loại phòng.", "Open reservations to check exact availability and select multiple room types.")}</p></div>
        <Link href="/reservation" className="shrink-0 rounded-xl bg-[#0F2A43] px-6 py-3 text-xs font-bold uppercase tracking-wider text-white">{localize("Kiểm tra phòng trống", "Check availability")}</Link>
      </div>

      {/* Introduction Section */}
      <section id="explore" className="deferred-section mx-auto max-w-4xl px-6 pb-12 pt-24 text-center">
        <p className="mb-4 text-xs font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Bộ sưu tập phòng", "Room collection")}</p>
        <h2 className="mb-6 font-serif text-4xl font-bold text-primary-navy md:text-5xl">
          {localize("Tìm hạng phòng phù hợp với cách bạn lưu trú.", "Find the room type that fits the way you stay.")}
        </h2>
        <p className="text-text-dark font-light text-sm md:text-base leading-loose max-w-3xl mx-auto">
          {localize("Mỗi loại phòng đều được thiết kế với không gian sáng, tiện nghi cần thiết và sức chứa rõ ràng để bạn dễ dàng lựa chọn cho kỳ lưu trú.", ROOMS_CONTENT.main.desc)}
        </p>
      </section>

      <section aria-label={localize("Lọc và sắp xếp hạng phòng", "Filter and sort room types")} className="mx-auto max-w-7xl px-6 pb-8">
        <div className="grid gap-4 border-y border-[#0F2A43]/12 bg-[#FBFAF6] py-5 md:grid-cols-[1.35fr_0.65fr_0.8fr_auto] md:items-end md:px-5">
          <label className="grid gap-2 text-xs font-bold text-[#66727C]">
            {localize("Tên phòng hoặc tiện nghi", "Room name or facility")}
            <input
              type="search"
              value={searchTerm}
              onChange={(event) => setSearchTerm(event.target.value)}
              placeholder={localize("Ví dụ: suite, hồ bơi, ban công...", "For example: suite, pool, balcony...")}
              className="min-h-12 rounded-lg border border-[#0F2A43]/14 bg-white px-4 text-sm font-medium text-[#0F2A43] outline-none transition placeholder:text-[#66727C]/65 focus:border-[#B8944F]"
            />
          </label>
          <label className="grid gap-2 text-xs font-bold text-[#66727C]">
            {localize("Sức chứa tối thiểu", "Minimum capacity")}
            <select value={minimumGuests} onChange={(event) => setMinimumGuests(event.target.value)} className="min-h-12 rounded-lg border border-[#0F2A43]/14 bg-white px-4 text-sm font-semibold text-[#0F2A43] outline-none transition focus:border-[#B8944F]">
              <option value="0">{localize("Tất cả", "Any")}</option>
              {[1, 2, 3, 4, 5, 6].map((count) => <option key={count} value={count}>{count}+ {localize("khách", count === 1 ? "guest" : "guests")}</option>)}
            </select>
          </label>
          <label className="grid gap-2 text-xs font-bold text-[#66727C]">
            {localize("Sắp xếp", "Sort by")}
            <select value={sortBy} onChange={(event) => setSortBy(event.target.value as typeof sortBy)} className="min-h-12 rounded-lg border border-[#0F2A43]/14 bg-white px-4 text-sm font-semibold text-[#0F2A43] outline-none transition focus:border-[#B8944F]">
              <option value="recommended">{localize("Đề xuất", "Recommended")}</option>
              <option value="price-asc">{localize("Giá thấp đến cao", "Price: low to high")}</option>
              <option value="price-desc">{localize("Giá cao đến thấp", "Price: high to low")}</option>
              <option value="rating">{localize("Đánh giá cao nhất", "Highest rated")}</option>
            </select>
          </label>
          <button type="button" onClick={resetDiscovery} className="inline-flex min-h-12 items-center justify-center rounded-lg border border-[#0F2A43]/16 bg-white px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F1F0EA]">
            {localize("Đặt lại", "Reset")}
          </button>
        </div>
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm text-[#66727C]" aria-live="polite">
          <p><strong className="tabular-nums text-[#0F2A43]">{filteredRoomTypes.length}</strong> {localize("hạng phòng phù hợp", "matching room types")}</p>
          <Link href={favoritesOnly ? "/rooms" : "/rooms?favorites=1"} className="inline-flex min-h-11 items-center gap-2 rounded-lg border border-[#0F2A43]/14 bg-white px-4 font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F0EADF]">
            <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4 text-[#80632F]" fill={favoriteCount ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8Z" />
            </svg>
            {favoritesOnly ? localize("Xem tất cả phòng", "View all rooms") : localize("Chỉ xem yêu thích", "Show favorites")}
            <span className="tabular-nums text-[#80632F]">{favoriteCount}</span>
          </Link>
        </div>
      </section>

      {/* Rooms Grid */}
      <section className="deferred-section max-w-7xl mx-auto px-6 pb-24">
        {isLoading || (favoritesOnly && !favoritesReady) ? (
          <div className="motion-stagger grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
            {[0, 1, 2, 3, 4, 5].map((item) => (
              <div key={item} className="overflow-hidden rounded-tl-2xl rounded-br-2xl bg-white shadow-sm">
                <div className="skeleton-surface aspect-[16/11]" />
                <div className="space-y-4 p-6">
                  <div className="skeleton-surface h-5 w-2/3" />
                  <div className="skeleton-surface h-4 w-full" />
                  <div className="skeleton-surface h-4 w-4/5" />
                </div>
              </div>
            ))}
          </div>
        ) : roomTypes.length === 0 ? (
          <div className="bg-[#F1F0EA] border border-[#0F2A43]/10 py-16 px-6 text-center rounded-tl-2xl rounded-br-2xl">
            <h3 className="font-serif text-3xl font-bold text-primary-navy">{localize("Chưa có loại phòng", "No room types available")}</h3>
            <p className="mt-3 text-sm text-text-light">{localize("Dữ liệu loại phòng hiện chưa sẵn sàng.", "Room type data is not available yet.")}</p>
          </div>
        ) : filteredRoomTypes.length === 0 ? (
          <div className="border-y border-[#0F2A43]/12 bg-[#FBFAF6] px-6 py-16 text-center">
            <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#80632F]">{favoritesOnly ? localize("Danh sách yêu thích", "Favorite rooms") : localize("Không có kết quả", "No matches")}</p>
            <h3 className="mt-3 font-serif text-3xl font-bold text-primary-navy">{favoritesOnly ? localize("Bạn chưa lưu hạng phòng nào", "You have not saved a room yet") : localize("Thử nới điều kiện tìm kiếm", "Try broadening your search")}</h3>
            <p className="mx-auto mt-3 max-w-xl text-sm leading-6 text-text-light">{favoritesOnly ? localize("Chạm biểu tượng trái tim trên một hạng phòng để lưu lại và xem nhanh tại đây.", "Select the heart on a room type to save it and find it here.") : localize("Xóa từ khóa hoặc chọn sức chứa thấp hơn để xem thêm hạng phòng.", "Clear the search term or lower the minimum capacity to see more room types.")}</p>
            {favoritesOnly ? (
              <Link href="/rooms" className="mt-6 inline-flex min-h-11 items-center rounded-lg bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#091E30]">{localize("Khám phá các hạng phòng", "Explore room types")}</Link>
            ) : (
              <button type="button" onClick={resetDiscovery} className="mt-6 inline-flex min-h-11 items-center rounded-lg bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#091E30]">{localize("Đặt lại bộ lọc", "Reset filters")}</button>
            )}
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
          {filteredRoomTypes.map((room, index) => {
            const title = localize(room.typeName, room.typeNameEn);
            const image = room.imageUrl;
            const price = room.price;
            const detailHref = room.id ? `/rooms/${room.id}` : "/rooms";
            const roomRating = {
              averageRating: Number(room.averageRating || 0),
              totalReviews: Number(room.totalReviews || 0),
            };
            const favorite = isFavorite(room.id);

            return (
              <article key={room.id || index} className="group flex h-full flex-col overflow-hidden rounded-[1.5rem] border border-[#0F2A43]/10 bg-[#FBFAF6] transition duration-300 hover:-translate-y-1 hover:border-[#B8944F]/70 hover:shadow-[0_20px_50px_rgba(15,42,67,0.12)]">
                <div className="relative">
                  <Link href={detailHref} className="relative block aspect-[4/3] overflow-hidden bg-[#E5E9ED] focus:outline-none focus:ring-2 focus:ring-inset focus:ring-[#B8944F]">
                    {image ? (
                      <ProgressiveImage
                        src={image}
                        alt={title}
                        fill
                        sizes="(min-width: 1280px) 33vw, (min-width: 768px) 50vw, 100vw"
                        className="object-cover group-hover:scale-105"
                      />
                    ) : (
                      <div className="absolute inset-0 flex items-center justify-center text-sm font-semibold text-[#66727C]">{localize("Chưa có hình ảnh", "No image")}</div>
                    )}
                    <div className="absolute inset-x-0 bottom-0 h-28 bg-gradient-to-t from-[#0F2A43]/80 to-transparent" />
                    <div className="absolute bottom-4 left-4 rounded-lg bg-[#FBFAF6] px-3 py-2 text-sm font-bold tabular-nums text-[#0F2A43] shadow-sm">
                      {Number(price || 0).toLocaleString(localeTag)} đ <span className="text-xs font-medium text-[#66727C]">/ {localize("giờ đầu", "first hour")}</span>
                    </div>
                  </Link>
                  <button
                    type="button"
                    aria-label={favorite ? localize(`Bỏ ${title} khỏi yêu thích`, `Remove ${title} from favorites`) : localize(`Thêm ${title} vào yêu thích`, `Add ${title} to favorites`)}
                    aria-pressed={favorite}
                    onClick={() => toggleFavorite(room.id)}
                    title={favorite ? localize("Bỏ yêu thích", "Remove favorite") : localize("Thêm vào yêu thích", "Add to favorites")}
                    className={`absolute right-4 top-4 inline-flex h-12 w-12 items-center justify-center rounded-full border-2 shadow-[0_8px_24px_rgba(15,42,67,0.18)] transition hover:scale-105 focus:outline-none focus:ring-2 focus:ring-[#B8944F] focus:ring-offset-2 ${favorite ? "border-white bg-rose-50 text-rose-600" : "border-white/90 bg-[#FBFAF6]/94 text-[#66727C] hover:text-rose-600"}`}
                  >
                    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-6 w-6" fill={favorite ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8Z" />
                    </svg>
                    <span className="sr-only">{favorite ? localize("Đã yêu thích", "Saved") : localize("Yêu thích", "Favorite")}</span>
                  </button>
                </div>

                <div className="flex flex-1 flex-col p-6 md:p-7">
                  <Link href={detailHref} className="group/title rounded focus:outline-none focus:ring-2 focus:ring-[#B8944F]">
                    <h3 className="font-serif text-2xl font-bold tracking-tight text-[#0F2A43] transition group-hover/title:text-[#80632F]">
                      {title}
                    </h3>
                  </Link>
                  <div className="mt-2 flex min-h-6 items-center gap-2 text-sm">
                    {roomRating?.totalReviews ? (
                      <>
                        <span className="font-bold tabular-nums text-[#80632F]">★ {roomRating.averageRating.toFixed(1)}</span>
                        <span className="text-xs font-medium text-[#66727C]">({roomRating.totalReviews} {localize("đánh giá", "reviews")})</span>
                      </>
                    ) : (
                      <span className="text-xs font-medium text-[#66727C]">{localize("Chưa có đánh giá", "No reviews yet")}</span>
                    )}
                  </div>
                  <p className="mt-2 text-sm font-semibold text-[#0F2A43]">{localize(`Tối đa ${room.maxGuests || 2} khách / phòng`, `Up to ${room.maxGuests || 2} guests / room`)}</p>
                  <p className="mt-3 line-clamp-3 text-sm leading-6 text-[#66727C]">
                    {localize(room.description, room.descriptionEn) || localize("Không gian nghỉ dưỡng tiện nghi, phù hợp cho kỳ lưu trú của bạn.", "A comfortable space suited to your stay.")}
                  </p>
                  <div className="mt-5 flex min-h-8 flex-wrap content-start gap-2">
                    {(room.facilities || []).slice(0, 3).map((facility) => (
                      <span key={facility.id || facility.facilityName} className="rounded-md bg-[#F0EADF] px-2.5 py-1 text-xs font-medium text-[#66727C]">
                        {localize(facility.facilityName, facility.facilityNameEn)}
                      </span>
                    ))}
                  </div>
                  <div className="mt-auto pt-6">
                    <Link 
                      href={detailHref}
                      className="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-lg bg-[#0F2A43] px-5 py-3 text-center text-sm font-bold text-white transition duration-200 hover:bg-[#091E30] focus:outline-none focus:ring-2 focus:ring-[#B8944F] focus:ring-offset-2 active:scale-[0.98]"
                    >
                      {localize("Xem chi tiết & đặt phòng", "View details & book")}
                      <span aria-hidden="true">→</span>
                    </Link>
                  </div>
                </div>
              </article>
            );
          })}
          </div>
        )}
      </section>

    </div>
  );
}

export default function RoomsPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-white" />}>
      <RoomsPageContent />
    </Suspense>
  );
}
