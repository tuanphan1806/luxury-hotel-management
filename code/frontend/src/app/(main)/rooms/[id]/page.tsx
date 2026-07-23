"use client";

import React, { useEffect, useState, use } from "react";
import axios from "axios";
import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { resolveMediaSource } from "@/lib/media-url";
import { apiClient } from "@/lib/api";
import Toast from "@/components/UI/Toast";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import DateTimeField from "@/components/forms/DateTimeField";
import { getPublicFacilities, getPublicRoomTypes } from "@/lib/public-catalog";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import ViewportModal from "@/components/UI/ViewportModal";
import GuestPageHero from "@/components/guest/GuestPageHero";
import FacilityDetailModal, { type FacilityDetailItem } from "@/components/guest/FacilityDetailModal";
import { ROOMS_CONTENT } from "@/constants/content";
import { useFavorites } from "@/components/favorites/FavoritesProvider";
import { getRoomGalleryImages, normalizeCatalogText } from "@/lib/room-gallery";


interface RoomDetails {
  id?: number;
  typeName: string;
  description: string;
  price: number | string;
  maxGuests: number;
  imageUrl: string;
  specs: {
    bed: string;
    size: string;
    view: string;
  };
  gallery: string[];
  amenities: RoomAmenity[];
}

interface RoomAmenity extends FacilityDetailItem {
  name: string;
}

interface ReviewItem {
  id?: number;
  userName?: string;
  userImageUrl?: string;
  roomTypeName?: string;
  rating?: number;
  comment?: string;
  createdAt?: string;
}

interface RoomTypeRating {
  averageRating?: number;
  totalReviews?: number;
}

interface RoomFacilityPayload {
  id?: number;
  facilityName?: string;
  facilityNameEn?: string;
  imageUrl?: string;
  type?: string;
}

interface RoomTypePayload {
  id: number | string;
  typeName?: string;
  typeNameEn?: string;
  description?: string;
  descriptionEn?: string;
  price?: number;
  maxGuests?: number;
  imageUrl?: string;
  facilities?: RoomFacilityPayload[];
  averageRating?: number;
  totalReviews?: number;
}

interface AvailabilityOption {
  roomTypeId: number | string;
  availableRooms?: number;
}

interface RoomRecommendation {
  id: string;
  title: string;
  desc: string;
  image?: string;
}

const getListPayload = (payload: unknown): ReviewItem[] => {
  if (Array.isArray(payload)) return payload as ReviewItem[];
  if (!payload || typeof payload !== "object") return [];

  const record = payload as Record<string, unknown>;
  if (Array.isArray(record.data)) return record.data as ReviewItem[];
  if (Array.isArray(record.content)) return record.content as ReviewItem[];
  if (record.data && typeof record.data === "object") {
    const nestedData = record.data as Record<string, unknown>;
    if (Array.isArray(nestedData.content)) return nestedData.content as ReviewItem[];
  }
  return [];
};

const getApiErrorMessage = (error: unknown, fallback: string) =>
  axios.isAxiosError<{ message?: string }>(error)
    ? error.response?.data?.message || fallback
    : fallback;

export default function RoomDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const router = useRouter();
  const { localeTag, localize } = useLanguage();
  const { isFavorite, toggleFavorite } = useFavorites();
  const resolvedParams = use(params);
  const roomId = resolvedParams.id;

  const [room, setRoom] = useState<RoomDetails | null>(null);
  const [reviews, setReviews] = useState<ReviewItem[]>([]);
  const [rating, setRating] = useState<RoomTypeRating | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const [selectedImage, setSelectedImage] = useState<number | null>(null);
  const [selectedFacility, setSelectedFacility] = useState<FacilityDetailItem | null>(null);
  const [selectedImageDirection, setSelectedImageDirection] = useState<"previous" | "next">("next");
  const galleryImages = room?.gallery ?? [];
  const selectedGalleryImage = selectedImage === null ? undefined : galleryImages[selectedImage];

  const [recommendations, setRecommendations] = useState<RoomRecommendation[]>([]);

  // Booking widget states
  const [checkIn, setCheckIn] = useState("");
  const [checkOut, setCheckOut] = useState("");
  const [adults, setAdults] = useState("1");
  const [childrenCount, setChildrenCount] = useState("0");
  const [quantity, setQuantity] = useState("1");
  const [isCheckingAvailability, setIsCheckingAvailability] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);

  const handleBookingCheck = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!checkIn || !checkOut) {
      setToast({ message: localize("Vui lòng chọn thời gian nhận và trả phòng.", "Please select check-in and check-out times."), type: "error" });
      return;
    }

    if (new Date(checkOut) <= new Date(checkIn)) {
      setToast({ message: localize("Thời gian trả phòng phải sau thời gian nhận phòng.", "Check-out must be after check-in."), type: "error" });
      return;
    }
    if (new Date(checkIn) <= new Date()) {
      setToast({ message: localize("Thời gian nhận phòng phải sau thời điểm hiện tại.", "Check-in must be after the current time."), type: "error" });
      return;
    }
    const requestedQuantity = Number(quantity);
    if (!Number.isInteger(requestedQuantity) || requestedQuantity < 1) {
      setToast({ message: "Số lượng phòng phải từ 1 trở lên.", type: "error" });
      return;
    }
    setIsCheckingAvailability(true);
    try {
      const response = await apiClient.get(`/api/reservations/availability?checkIn=${checkIn}:00&checkOut=${checkOut}:00`);
      const options: AvailabilityOption[] = Array.isArray(response.data?.data) ? response.data.data : [];
      const currentRoomType = options.find((option) => Number(option.roomTypeId) === Number(roomId));
      if (!currentRoomType || Number(currentRoomType.availableRooms || 0) < requestedQuantity) {
        setToast({ message: `Loại phòng này chỉ còn ${Number(currentRoomType?.availableRooms || 0)} phòng trong thời gian đã chọn.`, type: "error" });
        return;
      }
      const query = new URLSearchParams({ roomTypes: `${roomId}:${requestedQuantity}`, checkIn, checkOut, adults, children: childrenCount });
      router.push(`/booking?${query.toString()}`);
    } catch (error: unknown) {
      setToast({ message: getApiErrorMessage(error, "Không thể kiểm tra phòng trống."), type: "error" });
    } finally {
      setIsCheckingAvailability(false);
    }
  };

  useEffect(() => {
    const defaultCheckIn = new Date();
    defaultCheckIn.setSeconds(0, 0);
    defaultCheckIn.setMinutes(defaultCheckIn.getMinutes() + 30);
    const defaultCheckOut = new Date(defaultCheckIn);
    defaultCheckOut.setHours(defaultCheckOut.getHours() + 2);
    const localValue = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    setCheckIn(localValue(defaultCheckIn)); setCheckOut(localValue(defaultCheckOut));
  }, []);

  const showPreviousSelectedImage = () => {
    if (!galleryImages.length) return;
    setSelectedImageDirection("previous");
    setSelectedImage((prev) => {
      const current = prev ?? 0;
      return current <= 0 ? galleryImages.length - 1 : current - 1;
    });
  };

  const showNextSelectedImage = () => {
    if (!galleryImages.length) return;
    setSelectedImageDirection("next");
    setSelectedImage((prev) => {
      const current = prev ?? 0;
      return current >= galleryImages.length - 1 ? 0 : current + 1;
    });
  };

  useEffect(() => {

    // Dùng catalog dùng chung để không gọi lại RoomType cho phần chi tiết và gợi ý.
    Promise.all([
      getPublicRoomTypes<RoomTypePayload>(),
      getPublicFacilities<FacilityDetailItem>().catch(() => []),
    ])
      .then(([roomTypes, facilityCatalog]) => {
        const facilityById = new Map(facilityCatalog.map((facility) => [Number(facility.id), facility]));
        const dbData = roomTypes.find((item) => String(item.id) === String(roomId));
        if (dbData) {
          const normalizedType = normalizeCatalogText(`${dbData.typeName || ""} ${dbData.typeNameEn || ""}`);
          const specs = normalizedType.includes("presidential") || normalizedType.includes("tong thong")
            ? { bed: localize("1 giường King + phòng khách", "1 king bed + living room"), size: "90 m²", view: localize("Toàn cảnh thành phố", "Panoramic city view") }
            : normalizedType.includes("family") || normalizedType.includes("gia dinh")
              ? { bed: localize("2 giường Queen", "2 queen beds"), size: "62 m²", view: localize("Hướng sân vườn", "Garden view") }
              : normalizedType.includes("suite")
                ? { bed: localize("1 giường King", "1 king bed"), size: "55 m²", view: localize("Hướng thành phố", "City view") }
                : normalizedType.includes("executive")
                  ? { bed: localize("1 giường King", "1 king bed"), size: "40 m²", view: localize("Hướng thành phố", "City view") }
                  : normalizedType.includes("deluxe")
                    ? { bed: localize("1 giường King", "1 king bed"), size: "36 m²", view: localize("Ban công thành phố", "City balcony") }
                    : { bed: localize("1 giường Queen", "1 queen bed"), size: "28 m²", view: localize("Hướng sân trong", "Courtyard view") };
          // Map backend object format to page expectations
          setRoom({
            id: Number(dbData.id),
            typeName: localize(dbData.typeName, dbData.typeNameEn) || localize("Phòng nghỉ cao cấp", "Luxury room"),
            description: localize(dbData.description, dbData.descriptionEn) || localize("Không gian lưu trú chỉn chu, tiện nghi và sẵn sàng trước khi bạn đến.", "A calm stay with polished service, comfortable details, and everything ready before arrival."),
            price: dbData.price || 150,
            maxGuests: dbData.maxGuests || 2,
            imageUrl: dbData.imageUrl || "",
            specs,
            gallery: getRoomGalleryImages(dbData.typeName, dbData.typeNameEn, dbData.imageUrl),
            amenities: (dbData.facilities || []).map((facility) => {
              const detail = facility.id ? facilityById.get(Number(facility.id)) : undefined;
              const facilityName = facility.facilityName || detail?.facilityName || detail?.name;
              const facilityNameEn = facility.facilityNameEn || detail?.facilityNameEn;
              return {
                ...detail,
                id: facility.id ?? detail?.id,
                facilityName,
                facilityNameEn,
                name: localize(facilityName, facilityNameEn) || localize("Tiện nghi", "Amenity"),
                imageUrl: facility.imageUrl || detail?.imageUrl || detail?.image,
                type: facility.type || detail?.type,
              };
            })
          });
          setRating({
            averageRating: Number(dbData.averageRating || 0),
            totalReviews: Number(dbData.totalReviews || 0),
          });
          const recs: RoomRecommendation[] = roomTypes
            .filter((roomType) => String(roomType.id) !== String(roomId))
            .slice(0, 2)
            .map((roomType) => ({
              id: String(roomType.id),
              title: localize(roomType.typeName, roomType.typeNameEn),
              desc: `${localize(roomType.description, roomType.descriptionEn).substring(0, 70)}...`,
              image: roomType.imageUrl,
            }));
          setRecommendations(recs);
        } else {
          setRoom(null);
        }
      })
      .catch(err => {
        console.error("Error fetching room details from API:", err);
        setRoom(null);
      })
      .finally(() => {
        setIsLoading(false);
      });

    apiClient.get(`/api/reviews/room-type/${roomId}`)
      .then((res) => setReviews(getListPayload(res.data)))
      .catch((err) => {
        console.error("Failed to fetch room reviews:", err);
        setReviews([]);
      });

  }, [roomId, localize]);

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[#F1F0EA]">
        <div className="flex flex-col items-center gap-4">
          <div className="w-12 h-12 border-4 border-primary-navy border-t-accent-gold rounded-full animate-spin"></div>
          <p className="text-primary-navy font-semibold">{localize("Đang tải chi tiết phòng...", "Loading room details...")}</p>
        </div>
      </div>
    );
  }

  if (!room) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[#F1F0EA]">
        <p className="text-[#A66E6E] font-bold text-lg">{localize("Không tìm thấy loại phòng.", "Room type not found.")}</p>
      </div>
    );
  }

  const favoriteRoomId = Number(room.id || roomId);
  const favorite = isFavorite(favoriteRoomId);

  return (
    <div className="bg-[#F1F0EA]">
      <GuestPageHero
        imageSrc={room.imageUrl || ROOMS_CONTENT.hero.bg}
        imageAlt={localize(`Không gian hạng phòng ${room.typeName}`, `${room.typeName} room interior`)}
        eyebrow={localize("Phòng & hạng phòng", "Rooms & suites")}
        title={room.typeName}
        description={rating?.totalReviews
          ? localize(`${Number(rating.averageRating || 0).toFixed(1)} / 5 từ ${rating.totalReviews} lượt đánh giá · Tối đa ${room.maxGuests} khách / phòng`, `${Number(rating.averageRating || 0).toFixed(1)} / 5 from ${rating.totalReviews} reviews · Up to ${room.maxGuests} guests per room`)
          : localize(`Tối đa ${room.maxGuests} khách / phòng`, `Up to ${room.maxGuests} guests per room`)}
        actions={(
          <button
            type="button"
            onClick={() => toggleFavorite(favoriteRoomId)}
            aria-pressed={favorite}
            className={`inline-flex min-h-12 items-center gap-2 rounded-xl border px-5 text-sm font-bold shadow-sm transition hover:-translate-y-0.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D8C398] ${favorite ? "border-rose-200 bg-rose-50 text-rose-700" : "border-white/40 bg-[#FBFAF6]/95 text-[#0F2A43] hover:bg-white"}`}
          >
            <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill={favorite ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8Z" /></svg>
            {favorite ? localize("Đã lưu yêu thích", "Saved to favorites") : localize("Lưu vào yêu thích", "Save to favorites")}
          </button>
        )}
        contentPosition="left"
      />

      {/* Main Grid Content */}
      <section className="deferred-section max-w-6xl mx-auto px-6 py-16">
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-12 items-start">
          
          {/* Left Details Info */}
          <div className="lg:col-span-2 space-y-10">
            {/* Spec Badges Row */}
            <div className="grid gap-3 border-b border-[#0F2A43]/10 pb-6 text-sm font-medium text-text-light sm:grid-cols-2 xl:grid-cols-4">
              <div className="flex items-center gap-2">
                <span className="flex h-9 w-9 items-center justify-center rounded-full bg-[#EAE2D2] text-[#80632F]"><svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 19v-8m18 8v-6a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v6M3 16h18M6 11V8a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v3" /></svg></span>
                <span>{room.specs.bed}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="flex h-9 w-9 items-center justify-center rounded-full bg-[#EAE2D2] text-[#80632F]"><svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="m4 17 13-13 3 3L7 20H4v-3Z" /><path d="m13 8 3 3M9 12l3 3" /></svg></span>
                <span>{room.specs.size}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="flex h-9 w-9 items-center justify-center rounded-full bg-[#EAE2D2] text-[#80632F]"><svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M4 14a8 8 0 0 1 16 0M2 18h20M12 2v3M4.9 6.9 7 9m12.1-2.1L17 9" /></svg></span>
                <span>{room.specs.view}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="flex h-9 w-9 items-center justify-center rounded-full bg-[#EAE2D2] text-[#80632F]"><svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" /></svg></span>
                <span>{localize(`Tối đa ${room.maxGuests} khách / phòng`, `Up to ${room.maxGuests} guests / room`)}</span>
              </div>
            </div>

            {/* Description Text */}
            <div className="space-y-4">
              <h2 className="font-serif text-3xl font-bold text-primary-navy">
                {localize("Tổng quan loại phòng", "Room type overview")}
              </h2>
              <p className="text-text-light leading-relaxed text-sm md:text-base font-light">
                {room.description}
              </p>
            </div>

            {/* Room-specific editorial gallery */}
            <div className="space-y-5" data-guest-reveal>
              <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-end">
                <div>
                  <p className="text-[10px] font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Hình ảnh hạng phòng", "Room gallery")}</p>
                  <h3 className="mt-2 font-serif text-2xl font-bold text-primary-navy">{localize("Nhìn rõ từng không gian trước khi chọn", "See each space before you choose")}</h3>
                </div>
                <p className="max-w-sm text-sm leading-6 text-[#66727C]">{localize("Mỗi ảnh thể hiện một góc sử dụng khác nhau, không dùng ảnh tiện nghi chung để thay cho ảnh phòng.", "Each image shows a distinct room function; shared amenity photos are kept separate.")}</p>
              </div>

              {galleryImages.length ? (
                <div className={galleryImages.length >= 3
                  ? "grid gap-3 md:grid-cols-[1.45fr_0.85fr] md:grid-rows-2"
                  : "grid gap-3 md:grid-cols-2"}
                >
                  {galleryImages.slice(0, 3).map((img, index) => {
                    const caption = index === 0
                      ? localize("Toàn cảnh", "Overview")
                      : index === 1
                        ? localize("Không gian chức năng", "Functional space")
                        : localize("Chi tiết thiết kế", "Design detail");
                    const featured = galleryImages.length >= 3 && index === 0;
                    return (
                      <button
                        type="button"
                        key={img}
                        onClick={() => setSelectedImage(index)}
                        className={`guest-media-lift group relative overflow-hidden rounded-[1.35rem] bg-[#E5E9ED] text-left shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] ${featured ? "h-[360px] md:row-span-2 md:h-[540px]" : "h-[260px] md:h-auto md:min-h-[260px]"}`}
                        aria-label={localize(`Mở ảnh ${caption} của ${room.typeName}`, `Open ${caption} image for ${room.typeName}`)}
                      >
                        <ProgressiveImage
                          src={img}
                          fallbackSrc={room.imageUrl || ROOMS_CONTENT.hero.bg}
                          alt={`${room.typeName} — ${caption}`}
                          fill
                          sizes={featured ? "(min-width: 1024px) 55vw, 100vw" : "(min-width: 1024px) 30vw, 100vw"}
                          className="object-cover transition duration-500 group-hover:scale-[1.035]"
                          priority={index === 0}
                        />
                        <span className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-[#071A2A]/85 via-[#071A2A]/35 to-transparent px-5 pb-4 pt-16 text-sm font-bold text-white">
                          {caption}
                        </span>
                      </button>
                    );
                  })}
                </div>
              ) : (
                <div className="flex h-[260px] items-center justify-center rounded-[1.35rem] border border-dashed border-[#0F2A43]/15 bg-[#E5E9ED] text-sm font-semibold text-[#66727C]">
                  {localize("Chưa có hình ảnh cho loại phòng này.", "No gallery images are available for this room type.")}
                </div>
              )}
            </div>

            {/* Amenities Section */}
            <div className="rounded-[1.75rem] border border-[#0F2A43]/10 bg-[#E5E9ED] p-6 md:p-8" data-guest-reveal>
              <div className="mb-6 flex flex-col justify-between gap-3 border-b border-[#0F2A43]/10 pb-5 sm:flex-row sm:items-end">
                <div>
                  <p className="text-[10px] font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Đi kèm hạng phòng", "Included with this room")}</p>
                  <h3 className="mt-2 font-serif text-2xl font-bold text-primary-navy">{localize("Tiện nghi có hình ảnh đại diện", "Amenities with representative images")}</h3>
                </div>
                <Link href="/facilities" className="text-xs font-bold uppercase tracking-[0.14em] text-[#80632F] hover:underline">
                  {localize("Xem tất cả tiện nghi", "View all amenities")} →
                </Link>
              </div>
              {room.amenities.length ? (
                <div className="grid gap-4 sm:grid-cols-2">
                  {room.amenities.map((amenity, idx) => (
                    <button
                      type="button"
                      key={`${amenity.name}-${idx}`}
                      onClick={() => setSelectedFacility(amenity)}
                      aria-haspopup="dialog"
                      aria-label={localize(`Xem chi tiết tiện nghi ${amenity.name}`, `View details for ${amenity.name}`)}
                      className="group flex min-h-[108px] cursor-pointer overflow-hidden rounded-[1.1rem] border border-[#0F2A43]/10 bg-[#FBFAF6] text-left shadow-sm transition duration-200 ease-out hover:-translate-y-0.5 hover:border-[#B8944F]/65 hover:shadow-[0_14px_30px_rgba(15,42,67,0.13)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2"
                    >
                      <div className="relative w-[112px] shrink-0 overflow-hidden bg-[#D9E0E5]">
                        {amenity.imageUrl ? (
                          <ProgressiveImage
                            src={amenity.imageUrl}
                            fallbackSrc={room.imageUrl || ROOMS_CONTENT.hero.bg}
                            alt={localize(`Ảnh đại diện tiện nghi ${amenity.name}`, `${amenity.name} amenity`)}
                            fill
                            sizes="112px"
                            className="object-cover transition duration-500 group-hover:scale-105"
                          />
                        ) : (
                          <div className="flex h-full items-center justify-center text-[#80632F]" aria-hidden="true">
                            <svg viewBox="0 0 24 24" className="h-8 w-8" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M4 19V8l8-4 8 4v11M8 19v-5h8v5M3 19h18" /></svg>
                          </div>
                        )}
                      </div>
                      <div className="flex min-w-0 flex-1 flex-col justify-center p-4">
                        <div className="flex items-center justify-between gap-3">
                          <p className="text-[9px] font-bold uppercase tracking-[0.18em] text-[#80632F]">
                            {amenity.type === "ROOM" ? localize("Trong phòng", "In-room") : localize("Không gian chung", "Shared space")}
                          </p>
                          <span aria-hidden="true" className="text-sm font-bold text-[#80632F] transition-transform duration-200 group-hover:translate-x-0.5">↗</span>
                        </div>
                        <h4 className="mt-1 font-serif text-lg font-bold leading-tight text-[#0F2A43]">{amenity.name}</h4>
                        <p className="mt-2 flex items-center gap-2 text-xs font-semibold text-[#66727C]">
                          <svg viewBox="0 0 24 24" className="h-4 w-4 text-[#B8944F]" fill="none" stroke="currentColor" strokeWidth="2.2"><path d="m5 12 4 4L19 6" /></svg>
                          {localize("Có trong hạng phòng này", "Included with this room type")}
                        </p>
                      </div>
                    </button>
                  ))}
                </div>
              ) : (
                <div className="rounded-[1.1rem] border border-dashed border-[#0F2A43]/15 bg-[#FBFAF6] p-5 text-sm font-semibold text-[#66727C]">
                  {localize("Chưa có dữ liệu tiện nghi cho hạng phòng này.", "No amenity data is available for this room type yet.")}
                </div>
              )}
            </div>

            <div className="rounded-[1.75rem] bg-[#F1F0EA] p-6 md:p-8">
              <div className="mb-6 flex flex-col justify-between gap-3 sm:flex-row sm:items-end">
                <div>
                  <p className="mb-2 text-xs font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Đánh giá của khách", "Guest reviews")}</p>
                  <h3 className="font-serif text-3xl font-bold text-primary-navy">{localize("Trải nghiệm thực tế với loại phòng này", "Real stays in this room type")}</h3>
                </div>
                {rating?.totalReviews ? (
                  <div className="rounded-[1.15rem] bg-white px-4 py-3 text-right shadow-sm">
                    <p className="font-serif text-2xl font-bold text-[#80632F]">{Number(rating.averageRating || 0).toFixed(1)}</p>
                    <p className="text-xs font-semibold uppercase tracking-[0.14em] text-[#66727C]">{rating.totalReviews} {localize("lượt đánh giá", "reviews")}</p>
                  </div>
                ) : null}
              </div>

              {reviews.length ? (
                <div className="grid gap-4 md:grid-cols-2">
                  {reviews.slice(0, 4).map((review, index) => {
                    const score = Math.max(1, Math.min(5, Number(review.rating || 5)));
                    return (
                      <article key={review.id || `${review.userName}-${index}`} className="rounded-[1.25rem] bg-white p-5 shadow-sm">
                        <div className="flex items-start justify-between gap-4">
                          <div>
                            <h4 className="font-serif text-xl font-bold text-primary-navy">{review.userName || localize("Khách lưu trú", "Hotel guest")}</h4>
                            <div className="mt-2 flex gap-1 text-xs text-[#B8944F]" aria-label={localize(`${score} trên 5 sao`, `${score} out of 5 stars`)}>
                              {Array.from({ length: 5 }).map((_, starIndex) => (
                                <span key={starIndex}>{starIndex < score ? "★" : "☆"}</span>
                              ))}
                            </div>
                          </div>
                          {review.userImageUrl ? (
                            <Image src={resolveMediaSource(review.userImageUrl)} alt={review.userName || localize("Khách", "Guest")} width={40} height={40} className="h-10 w-10 rounded-full object-cover" />
                          ) : (
                            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-[#E5E9ED] font-serif font-bold text-[#80632F]">
                              {(review.userName || "G").charAt(0)}
                            </div>
                          )}
                        </div>
                        <p className="mt-4 text-sm font-medium leading-7 text-[#66727C]">
                          &quot;{review.comment || localize("Kỳ nghỉ thoải mái, phục vụ thân thiện và quá trình đặt phòng thuận tiện.", "A comfortable stay with warm service and a smooth booking experience.")}&quot;
                        </p>
                      </article>
                    );
                  })}
                </div>
              ) : (
                <div className="rounded-[1.25rem] border border-dashed border-[#0F2A43]/15 bg-white/70 p-6">
                  <h4 className="font-serif text-xl font-bold text-primary-navy">{localize("Chưa có đánh giá", "No reviews yet")}</h4>
                  <p className="mt-2 text-sm leading-6 text-[#66727C]">
                    {localize("Đánh giá sẽ xuất hiện sau khi khách hoàn tất kỳ nghỉ và gửi nhận xét cho loại phòng này.", "Reviews appear after guests complete their stay and share feedback for this room type.")}
                  </p>
                </div>
              )}
            </div>
          </div>

          {/* Quick booking for this room type only */}
          <aside className="sticky top-28 space-y-6 rounded-[1.5rem] border border-[#0F2A43]/10 bg-white p-7 shadow-lg">
            <div>
              <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Bước tiếp theo", "Next step")}</p>
              <h3 className="mt-2 font-serif text-2xl font-bold text-primary-navy">{localize("Kiểm tra phòng trống", "Check availability")}</h3>
              <p className="mt-2 text-sm font-medium leading-6 text-text-light">{localize("Đặt nhanh loại phòng đang xem. Nếu cần nhiều loại phòng, hãy dùng trang đặt phòng chi tiết.", "Book this room type directly. Use the detailed reservation page when you need multiple room types.")}</p>
            </div>
            <div className="space-y-3 border-y border-[#0F2A43]/10 py-4 text-sm"><div className="flex justify-between"><span className="text-[#66727C]">{localize("Loại phòng", "Room type")}</span><strong className="text-[#0F2A43]">{room.typeName}</strong></div><div className="flex justify-between"><span className="text-[#66727C]">{localize("Sức chứa", "Capacity")}</span><strong className="text-[#0F2A43]">{localize(`${room.maxGuests} khách / phòng`, `${room.maxGuests} guests / room`)}</strong></div><div className="flex justify-between"><span className="text-[#66727C]">{localize("Giá cơ bản", "Base price")}</span><strong className="text-[#80632F]">{Number(room.price || 0).toLocaleString(localeTag)} đ</strong></div></div>
            <form onSubmit={handleBookingCheck} className="space-y-4">
              <DateTimeField label={`${localize("Thời gian nhận phòng", "Check-in time")} *`} value={checkIn} onValueChange={setCheckIn} />
              <DateTimeField label={`${localize("Thời gian trả phòng", "Check-out time")} *`} value={checkOut} min={checkIn || undefined} onValueChange={setCheckOut} />
              <div className="grid grid-cols-3 gap-3">
                <label><span className="mb-2 block text-[10px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Người lớn", "Adults")}</span><input type="number" min="1" value={adults} onChange={(event) => setAdults(event.target.value)} className="w-full rounded-xl border border-[#0F2A43]/15 px-3 py-3 text-sm" /></label>
                <label><span className="mb-2 block text-[10px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Trẻ em", "Children")}</span><input type="number" min="0" value={childrenCount} onChange={(event) => setChildrenCount(event.target.value)} className="w-full rounded-xl border border-[#0F2A43]/15 px-3 py-3 text-sm" /></label>
                <label><span className="mb-2 block text-[10px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Số phòng", "Rooms")}</span><input type="number" min="1" value={quantity} onChange={(event) => setQuantity(event.target.value)} className="w-full rounded-xl border border-[#0F2A43]/15 px-3 py-3 text-sm" /></label>
              </div>
              <button disabled={isCheckingAvailability} className="flex w-full items-center justify-center rounded-xl bg-[#0F2A43] px-5 py-4 text-xs font-bold uppercase tracking-wider text-white transition hover:bg-[#091E30] disabled:opacity-50">{isCheckingAvailability ? localize("Đang kiểm tra...", "Checking...") : localize("Kiểm tra và tiếp tục", "Check and continue")}</button>
            </form>
            <Link href={`/reservation?roomTypeId=${roomId}`} className="block text-center text-[11px] font-bold text-[#80632F] hover:underline">{localize("Cần đặt nhiều loại phòng? Mở trang đặt phòng chi tiết", "Need multiple room types? Open the detailed reservation page")}</Link>
          </aside>
        </div>
      </section>

      {/* Bottom Recommendation Slider */}
      <section className="deferred-section bg-[#E5E9ED]/40 border-t border-gray-100 py-20">
        <div className="max-w-6xl mx-auto px-6">
          <div className="flex items-center justify-between mb-12">
            <h2 className="font-serif text-3xl md:text-4xl font-bold text-primary-navy">
              {localize("Thêm lựa chọn phòng", "More room choices")}
            </h2>
            <Link href="/rooms" className="text-xs font-bold text-accent-gold hover:underline tracking-wider uppercase">
              {localize("TẤT CẢ PHÒNG", "ALL ROOMS")} &rarr;
            </Link>
          </div>

          <div className="motion-stagger grid grid-cols-1 md:grid-cols-2 gap-8">
            {recommendations.map((rec) => (
              <article key={rec.id} className="bg-white p-6 rounded-sm shadow-sm">
                {rec.image ? (
                  <div className="relative mb-4 h-[200px] w-full overflow-hidden bg-[#E5E9ED]">
                    <ProgressiveImage src={rec.image} alt={rec.title} fill sizes="(min-width: 768px) 50vw, 100vw" className="object-cover" />
                  </div>
                ) : (
                  <div className="mb-4 flex h-[200px] w-full items-center justify-center bg-[#E5E9ED] text-sm font-semibold text-[#66727C]">{localize("Chưa có ảnh", "No image")}</div>
                )}
                <h3 className="font-serif text-2xl font-bold">{rec.title}</h3>
                <p className="text-sm text-text-light py-2">{rec.desc}</p>
                <Link href={`/rooms/${rec.id}`} className="text-accent-gold font-bold uppercase text-xs">{localize("Xem chi tiết", "View details")} &rarr;</Link>
              </article>
            ))}
          </div>
        </div>
      </section>

      {selectedImage !== null && selectedGalleryImage && (
        <ViewportModal
          open
          onClose={() => setSelectedImage(null)}
          labelledBy="room-gallery-lightbox-title"
          panelClassName="max-w-[96vw] !border-0 !bg-transparent !shadow-none"
          backdropClassName="bg-black/90"
        >
          <div
            className="relative flex min-h-0 flex-1 items-center justify-center"
            onMouseDown={(event) => {
              if (event.target === event.currentTarget) setSelectedImage(null);
            }}
          >
          <h2 id="room-gallery-lightbox-title" className="sr-only">
            {localize("Ảnh chi tiết phòng", "Room gallery image")}
          </h2>
          <button
              type="button"
              className="absolute right-2 top-2 z-10 flex h-11 w-11 items-center justify-center rounded-full bg-[#0F2A43]/80 text-2xl text-white shadow-lg backdrop-blur transition hover:scale-105 hover:bg-[#0F2A43] sm:right-4 sm:top-4"
              onClick={() => setSelectedImage(null)}
              aria-label={localize("Đóng ảnh", "Close image")}
          >
              ✕
          </button>

          {galleryImages.length > 1 && (
              <button
                  type="button"
                  className="absolute left-2 z-10 flex h-11 w-11 items-center justify-center rounded-full bg-[#F1F0EA]/90 text-3xl text-[#0F2A43] shadow-lg backdrop-blur transition hover:scale-105 hover:bg-white sm:left-4"
                  onClick={(e) => {
                      e.stopPropagation();
                      showPreviousSelectedImage();
                  }}
                  aria-label={localize("Ảnh trước", "Previous image")}
              >
                  ‹
              </button>
          )}

          <Image
              key={`${selectedImage}-${selectedImageDirection}`}
              src={resolveMediaSource(selectedGalleryImage)}
              alt={`${room.typeName} selected gallery`}
              width={1600}
              height={1000}
              className={`h-auto w-auto max-h-[calc(100dvh-2rem)] max-w-[94vw] object-contain ${
                selectedImageDirection === "next" ? "facility-lightbox-next" : "facility-lightbox-previous"
              }`}
              onClick={(e) => e.stopPropagation()}
          />

          {galleryImages.length > 1 && (
              <button
                  type="button"
                  className="absolute right-2 z-10 flex h-11 w-11 items-center justify-center rounded-full bg-[#F1F0EA]/90 text-3xl text-[#0F2A43] shadow-lg backdrop-blur transition hover:scale-105 hover:bg-white sm:right-4"
                  onClick={(e) => {
                      e.stopPropagation();
                      showNextSelectedImage();
                  }}
                  aria-label={localize("Ảnh tiếp theo", "Next image")}
              >
                  ›
              </button>
          )}
          </div>
        </ViewportModal>
      )}
      <FacilityDetailModal facility={selectedFacility} onClose={() => setSelectedFacility(null)} />
      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
