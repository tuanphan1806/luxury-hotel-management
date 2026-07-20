"use client";

import React, { useEffect, useState, use } from "react";
import axios from "axios";
import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { apiClient } from "@/lib/api";
import Toast from "@/components/UI/Toast";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import DateTimeField from "@/components/forms/DateTimeField";
import { getPublicRoomTypes } from "@/lib/public-catalog";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import GuestPageHero from "@/components/guest/GuestPageHero";
import { ROOMS_CONTENT } from "@/constants/content";


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
  amenities: string[];
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
  facilityName?: string;
  facilityNameEn?: string;
  imageUrl?: string;
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
  const resolvedParams = use(params);
  const roomId = resolvedParams.id;

  const [room, setRoom] = useState<RoomDetails | null>(null);
  const [reviews, setReviews] = useState<ReviewItem[]>([]);
  const [rating, setRating] = useState<RoomTypeRating | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const [galleryStart, setGalleryStart] = useState(0);
  const [selectedImage, setSelectedImage] = useState<number | null>(null);
  const [galleryDirection, setGalleryDirection] = useState<"previous" | "next">("next");
  const [selectedImageDirection, setSelectedImageDirection] = useState<"previous" | "next">("next");
  const galleryImages = room?.gallery ?? [];
  const maxGalleryStart = Math.max(0, galleryImages.length - 3);
  const visibleImages = galleryImages.slice(galleryStart, galleryStart + 3);
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

  const showPreviousGallerySet = () => {
    if (!galleryImages.length) return;
    setGalleryDirection("previous");
    setGalleryStart((prev) => (prev <= 0 ? maxGalleryStart : Math.max(prev - 1, 0)));
  };

  const showNextGallerySet = () => {
    if (!galleryImages.length) return;
    setGalleryDirection("next");
    setGalleryStart((prev) => (prev >= maxGalleryStart ? 0 : Math.min(prev + 1, maxGalleryStart)));
  };

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
    getPublicRoomTypes<RoomTypePayload>()
      .then((roomTypes) => {
        const dbData = roomTypes.find((item) => String(item.id) === String(roomId));
        if (dbData) {
          const facilityImages = (dbData.facilities || [])
            .map((facility) => facility.imageUrl)
            .filter((image): image is string => Boolean(image));
          // Map backend object format to page expectations
          setRoom({
            id: Number(dbData.id),
            typeName: localize(dbData.typeName, dbData.typeNameEn) || localize("Phòng nghỉ cao cấp", "Luxury room"),
            description: localize(dbData.description, dbData.descriptionEn) || localize("Không gian lưu trú chỉn chu, tiện nghi và sẵn sàng trước khi bạn đến.", "A calm stay with polished service, comfortable details, and everything ready before arrival."),
            price: dbData.price || 150,
            maxGuests: dbData.maxGuests || 2,
            imageUrl: dbData.imageUrl || "",
            specs: {
              bed: localize("1 giường cỡ lớn", "1 king bed"),
              size: "42 m²",
              view: localize("Hướng vườn", "Garden view")
            },
            gallery: facilityImages,
            amenities: dbData.facilities?.map((facility) => localize(facility.facilityName, facility.facilityNameEn)) || [
              localize("Chưa có dữ liệu tiện ích", "No facility data available")
            ]
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

  return (
    <div className="bg-[#F1F0EA]">
      <GuestPageHero
        imageSrc={room.imageUrl || ROOMS_CONTENT.hero.bg}
        imageAlt={localize(`Không gian hạng phòng ${room.typeName}`, `${room.typeName} room interior`)}
        useGallery
        galleryKeywords={[room.typeName, "phòng khách trong phòng", "hotel room", "room"]}
        galleryIndex={Number(room.id)}
        eyebrow={localize("Phòng & hạng phòng", "Rooms & suites")}
        title={room.typeName}
        description={rating?.totalReviews
          ? localize(`${Number(rating.averageRating || 0).toFixed(1)} / 5 từ ${rating.totalReviews} lượt đánh giá · Tối đa ${room.maxGuests} khách / phòng`, `${Number(rating.averageRating || 0).toFixed(1)} / 5 from ${rating.totalReviews} reviews · Up to ${room.maxGuests} guests per room`)
          : localize(`Tối đa ${room.maxGuests} khách / phòng`, `Up to ${room.maxGuests} guests per room`)}
      />

      {/* Main Grid Content */}
      <section className="deferred-section max-w-6xl mx-auto px-6 py-16">
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-12 items-start">
          
          {/* Left Details Info */}
          <div className="lg:col-span-2 space-y-10">
            {/* Spec Badges Row */}
            <div className="flex flex-wrap gap-6 items-center border-b border-gray-100 pb-6 text-sm text-text-light font-medium">
              <div className="flex items-center gap-2">
                <span className="text-accent-gold text-lg">🛏</span>
                <span>{room.specs.bed}</span>
              </div>
              <div className="w-1.5 h-1.5 rounded-full bg-gray-300 hidden sm:block"></div>
              <div className="flex items-center gap-2">
                <span className="text-accent-gold text-lg">📐</span>
                <span>{room.specs.size}</span>
              </div>
              <div className="w-1.5 h-1.5 rounded-full bg-gray-300 hidden sm:block"></div>
              <div className="flex items-center gap-2">
                <span className="text-accent-gold text-lg">🌅</span>
                <span>{room.specs.view}</span>
              </div>
              <div className="w-1.5 h-1.5 rounded-full bg-gray-300 hidden sm:block"></div>
              <div className="flex items-center gap-2">
                <span className="text-accent-gold text-lg">●</span>
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

            {/* Grid of Images */}
            <div className="flex items-center gap-4">

            {/* Left Arrow */}
            <button
                disabled={!galleryImages.length}
                onClick={showPreviousGallerySet}
                className="w-10 h-10 rounded-full border border-gray-300 flex items-center justify-center
                hover:bg-primary-navy hover:text-white transition
                disabled:cursor-not-allowed disabled:opacity-40 shrink-0"
            >
                ←
            </button>

            {/* Facility Images */}
            <div
              key={`${galleryStart}-${galleryDirection}`}
              className="grid flex-1 grid-cols-1 gap-4 md:grid-cols-3"
            >
                {visibleImages.map((img, index) => (
                    <div
                        key={`${img}-${galleryStart + index}`}
                        className={`facility-gallery-card relative h-[250px] cursor-pointer overflow-hidden rounded-sm shadow-sm ${
                          galleryDirection === "next" ? "facility-gallery-card-next" : "facility-gallery-card-previous"
                        }`}
                        style={{
                          animationDelay: `${(galleryDirection === "next" ? index : visibleImages.length - 1 - index) * 95}ms`,
                        }}
                        onClick={() => setSelectedImage(galleryStart + index)}
                    >
                        <ProgressiveImage
                            src={img}
                            alt={`${room.typeName} gallery ${galleryStart + index + 1}`}
                            fill
                            sizes="(min-width: 768px) 33vw, 100vw"
                            className="object-cover hover:scale-105"
                        />
                    </div>
                ))}
                {!visibleImages.length && (
                  <div className="flex h-[250px] items-center justify-center rounded-sm border border-dashed border-gray-200 bg-[#F1F0EA] text-sm font-semibold text-[#66727C] md:col-span-3">
                    {localize("Chưa có hình ảnh cho loại phòng này.", "No gallery images are available for this room type.")}
                  </div>
                )}
            </div>

            {/* Right Arrow */}
            <button
                disabled={!galleryImages.length}
                onClick={showNextGallerySet}
                className="w-10 h-10 rounded-full border border-gray-300 flex items-center justify-center
                hover:bg-primary-navy hover:text-white transition
                disabled:cursor-not-allowed disabled:opacity-40 shrink-0"
            >
                →
            </button>

        </div>

            {/* Amenities Section */}
            <div className="bg-[#E5E9ED] p-8 rounded-sm border border-gray-100/50">
              <h3 className="font-serif text-xl font-bold text-primary-navy mb-6 pb-2 border-b border-gray-200/50">
                {localize("Tiện ích đi kèm", "Included amenities")}
              </h3>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-y-4 gap-x-8">
                {room.amenities.map((amenity, idx) => (
                  <div key={idx} className="flex items-center gap-3 text-sm text-text-dark font-medium">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#B8944F" strokeWidth="2.5" className="shrink-0">
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                    <span>{amenity}</span>
                  </div>
                ))}
              </div>
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
                            <Image src={review.userImageUrl} alt={review.userName || localize("Khách", "Guest")} width={40} height={40} className="h-10 w-10 rounded-full object-cover" />
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
      <div
          className="fixed inset-0 z-50 bg-black/90 flex items-center justify-center"
          onClick={() => setSelectedImage(null)}
      >
          <button
              className="absolute top-5 right-5 text-white text-4xl"
              onClick={() => setSelectedImage(null)}
          >
              ✕
          </button>

          {galleryImages.length > 1 && (
              <button
                  className="absolute left-6 text-white text-5xl"
                  onClick={(e) => {
                      e.stopPropagation();
                      showPreviousSelectedImage();
                  }}
              >
                  ‹
              </button>
          )}

          <Image
              key={`${selectedImage}-${selectedImageDirection}`}
              src={selectedGalleryImage}
              alt={`${room.typeName} selected gallery`}
              width={1600}
              height={1000}
              className={`h-auto w-auto max-h-[90vh] max-w-[90vw] object-contain ${
                selectedImageDirection === "next" ? "facility-lightbox-next" : "facility-lightbox-previous"
              }`}
              onClick={(e) => e.stopPropagation()}
          />

          {galleryImages.length > 1 && (
              <button
                  className="absolute right-6 text-white text-5xl"
                  onClick={(e) => {
                      e.stopPropagation();
                      showNextSelectedImage();
                  }}
              >
                  ›
              </button>
          )}
      </div>
  )}
      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
