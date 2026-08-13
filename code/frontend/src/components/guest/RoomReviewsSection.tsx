"use client";

import Image from "next/image";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import ViewportModal from "@/components/UI/ViewportModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { apiClient } from "@/lib/api";
import { resolveMediaSource } from "@/lib/media-url";
import {
  emptyRoomReviewPage,
  mergeRoomReviews,
  parseRoomReviewPage,
  type PublicRoomReview,
  type PublicRoomReviewPage,
} from "@/lib/room-reviews";

type ReviewSort = "newest" | "highest" | "lowest";

interface RoomReviewsSectionProps {
  roomTypeId: string;
  averageRating: number;
  totalReviews: number;
}

const PAGE_SIZE = 6;
const PREVIEW_COUNT = 3;

function ReviewCard({ review, detailed = false }: { review: PublicRoomReview; detailed?: boolean }) {
  const { localize, localeTag } = useLanguage();
  const [showFullComment, setShowFullComment] = useState(false);
  const score = Math.max(1, Math.min(5, Number(review.rating || 5)));
  const reviewer = review.userName || localize("Khách lưu trú", "Hotel guest");
  const comment = review.comment?.trim();
  const isLongComment = Boolean(comment && comment.length > 220);
  const reviewDate = useMemo(() => {
    if (!review.createdAt) return "";
    const parsed = new Date(review.createdAt);
    if (Number.isNaN(parsed.getTime())) return "";
    return new Intl.DateTimeFormat(localeTag, {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
    }).format(parsed);
  }, [localeTag, review.createdAt]);

  return (
    <article className={`flex h-full flex-col rounded-[1.15rem] border border-[#0F2A43]/9 bg-white shadow-[0_8px_24px_rgba(15,42,67,0.055)] ${detailed ? "p-5 sm:p-6" : "p-4 sm:p-5"}`}>
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <h4 className={`truncate font-serif font-bold text-primary-navy ${detailed ? "text-xl" : "text-lg"}`}>{reviewer}</h4>
          <div className="mt-1.5 flex min-w-0 flex-nowrap items-center gap-2">
            <div className="flex shrink-0 gap-0.5 whitespace-nowrap text-[13px] leading-none text-[#B8944F]" aria-label={localize(`${score} trên 5 sao`, `${score} out of 5 stars`)}>
              {Array.from({ length: 5 }).map((_, starIndex) => (
                <span key={starIndex} aria-hidden="true">{starIndex < score ? "★" : "☆"}</span>
              ))}
            </div>
            {reviewDate && <time className="shrink-0 whitespace-nowrap text-[11px] font-semibold tabular-nums text-[#66727C]" dateTime={review.createdAt}>{reviewDate}</time>}
          </div>
        </div>
        {review.userImageUrl ? (
          <Image src={resolveMediaSource(review.userImageUrl)} alt={reviewer} width={40} height={40} className="h-10 w-10 shrink-0 rounded-full border border-[#0F2A43]/10 object-cover" />
        ) : (
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[#EAE2D2] font-serif font-bold text-[#80632F]">
            {reviewer.charAt(0).toUpperCase()}
          </div>
        )}
      </div>

      <div className={`${detailed ? "mt-4" : "mt-3"} flex-1`}>
        {comment ? (
          <p className={`text-sm font-medium text-[#4F5E69] ${detailed ? "leading-7" : "leading-6"} ${!showFullComment ? (detailed ? "line-clamp-4" : "line-clamp-3") : ""}`}>
            &ldquo;{comment}&rdquo;
          </p>
        ) : (
          <p className="text-sm font-medium italic leading-7 text-[#66727C]">
            {localize(`Khách đã chấm ${score}/5 sao và không để lại nhận xét.`, `The guest rated this stay ${score}/5 without a written comment.`)}
          </p>
        )}
        {detailed && isLongComment && (
          <button type="button" onClick={() => setShowFullComment((current) => !current)} className="mt-2 text-xs font-bold text-[#80632F] underline decoration-[#B8944F]/50 underline-offset-4 transition hover:text-[#0F2A43] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
            {showFullComment ? localize("Thu gọn", "Show less") : localize("Xem đầy đủ", "Read more")}
          </button>
        )}
      </div>
    </article>
  );
}

export default function RoomReviewsSection({ roomTypeId, averageRating, totalReviews }: RoomReviewsSectionProps) {
  const { localize } = useLanguage();
  const requestSequence = useRef(0);
  const [reviews, setReviews] = useState<PublicRoomReview[]>([]);
  const [reviewPage, setReviewPage] = useState<PublicRoomReviewPage>(() => emptyRoomReviewPage(PAGE_SIZE));
  const [sort, setSort] = useState<ReviewSort>("newest");
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [error, setError] = useState("");
  const [modalOpen, setModalOpen] = useState(false);

  const loadPage = useCallback(async (page: number, append: boolean, requestedSort: ReviewSort) => {
    const sequence = ++requestSequence.current;
    if (append) setIsLoadingMore(true);
    else setIsLoading(true);
    setError("");

    try {
      const response = await apiClient.get(`/api/reviews/room-type/${roomTypeId}/page`, {
        params: { page, size: PAGE_SIZE, sort: requestedSort },
      });
      if (sequence !== requestSequence.current) return;
      const nextPage = parseRoomReviewPage(response.data, PAGE_SIZE);
      setReviewPage(nextPage);
      setReviews((current) => {
        if (!append) return nextPage.content;
        return mergeRoomReviews(current, nextPage.content);
      });
    } catch {
      if (sequence !== requestSequence.current) return;
      setError(localize("Không thể tải đánh giá lúc này. Vui lòng thử lại.", "Reviews could not be loaded. Please try again."));
      if (!append) {
        setReviews([]);
        setReviewPage(emptyRoomReviewPage(PAGE_SIZE));
      }
    } finally {
      if (sequence === requestSequence.current) {
        setIsLoading(false);
        setIsLoadingMore(false);
      }
    }
  }, [localize, roomTypeId]);

  useEffect(() => {
    setReviews([]);
    setReviewPage(emptyRoomReviewPage(PAGE_SIZE));
    void loadPage(0, false, sort);
  }, [loadPage, sort]);

  const hasInitialError = Boolean(error && reviews.length === 0);
  const displayedTotal = isLoading || hasInitialError
    ? totalReviews
    : reviewPage.totalElements;
  const previewReviews = reviews.slice(0, PREVIEW_COUNT);
  const showMoreButton = displayedTotal > PREVIEW_COUNT;

  const changeSort = (nextSort: ReviewSort) => {
    if (nextSort === sort) return;
    setSort(nextSort);
  };

  return (
    <>
      <section className="rounded-[1.75rem] bg-[#F1F0EA] p-5 md:p-7" aria-labelledby="room-reviews-title">
        <div className="mb-5 flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
          <div>
            <p className="mb-2 text-xs font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Đánh giá của khách", "Guest reviews")}</p>
            <h3 id="room-reviews-title" className="font-serif text-2xl font-bold text-primary-navy md:text-3xl">{localize("Trải nghiệm thực tế với loại phòng này", "Real stays in this room type")}</h3>
          </div>
          {displayedTotal > 0 && (
            <div className="inline-flex w-fit shrink-0 flex-nowrap items-center gap-2.5 rounded-full border border-[#0F2A43]/8 bg-white px-4 py-2.5 shadow-sm">
              <strong className="font-serif text-xl leading-none text-[#80632F]">{Number(averageRating || 0).toFixed(1)}</strong>
              <span className="flex shrink-0 gap-0.5 whitespace-nowrap text-[13px] leading-none text-[#B8944F]" aria-label={localize(`${Number(averageRating || 0).toFixed(1)} trên 5 sao`, `${Number(averageRating || 0).toFixed(1)} out of 5 stars`)}>
                {Array.from({ length: 5 }).map((_, index) => <span key={index} aria-hidden="true">{index < Math.round(averageRating || 0) ? "★" : "☆"}</span>)}
              </span>
              <span className="shrink-0 whitespace-nowrap border-l border-[#0F2A43]/10 pl-2.5 text-xs font-semibold text-[#66727C]">{displayedTotal} {localize("đánh giá", displayedTotal === 1 ? "review" : "reviews")}</span>
            </div>
          )}
        </div>

        {isLoading ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-label={localize("Đang tải đánh giá", "Loading reviews")}>
            {Array.from({ length: PREVIEW_COUNT }).map((_, index) => <div key={index} className="skeleton-surface h-52 rounded-[1.25rem]" />)}
          </div>
        ) : hasInitialError ? (
          <div className="rounded-[1.25rem] border border-amber-200 bg-amber-50 p-6 text-center">
            <p role="alert" className="text-sm font-semibold text-amber-900">{error}</p>
            <button type="button" onClick={() => void loadPage(0, false, sort)} className="mt-4 min-h-11 rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#091E30] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
              {localize("Thử lại", "Try again")}
            </button>
          </div>
        ) : previewReviews.length ? (
          <>
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {previewReviews.map((review, index) => <ReviewCard key={review.id || `${review.userName}-${index}`} review={review} />)}
            </div>
            {showMoreButton && (
              <div className="mt-6 flex justify-center">
                <button type="button" onClick={() => setModalOpen(true)} className="group inline-flex min-h-12 items-center justify-center gap-2 rounded-xl border border-[#0F2A43]/16 bg-white px-6 text-sm font-bold text-[#0F2A43] shadow-sm transition duration-200 hover:-translate-y-0.5 hover:border-[#B8944F] hover:bg-[#FBFAF6] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
                  {localize("Xem thêm", "View more")}
                  <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4 transition-transform group-hover:translate-y-0.5" fill="none" stroke="currentColor" strokeWidth="2"><path d="m7 10 5 5 5-5" strokeLinecap="round" strokeLinejoin="round" /></svg>
                </button>
              </div>
            )}
          </>
        ) : (
          <div className="rounded-[1.25rem] border border-dashed border-[#0F2A43]/15 bg-white/70 p-6">
            <h4 className="font-serif text-xl font-bold text-primary-navy">{localize("Chưa có đánh giá", "No reviews yet")}</h4>
            <p className="mt-2 text-sm leading-6 text-[#66727C]">{localize("Đánh giá sẽ xuất hiện sau khi khách hoàn tất kỳ nghỉ và gửi nhận xét cho loại phòng này.", "Reviews appear after guests complete their stay and share feedback for this room type.")}</p>
          </div>
        )}
      </section>

      <ViewportModal open={modalOpen} onClose={() => setModalOpen(false)} labelledBy="all-room-reviews-title" panelClassName="max-w-5xl" testId="room-reviews-modal">
        <header className="relative shrink-0 bg-[#0F2A43] px-5 py-5 text-white sm:px-7 sm:py-6">
          <button type="button" data-modal-autofocus onClick={() => setModalOpen(false)} aria-label={localize("Đóng danh sách đánh giá", "Close review list")} className="absolute right-4 top-4 flex h-11 w-11 items-center justify-center rounded-full border border-white/18 bg-white/8 text-xl transition hover:bg-white/15 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">×</button>
          <p className="pr-14 text-[10px] font-bold uppercase tracking-[0.22em] text-[#D8C398]">{localize("Đánh giá của khách", "Guest reviews")}</p>
          <h2 id="all-room-reviews-title" className="mt-2 pr-14 font-serif text-2xl font-bold sm:text-3xl">{localize("Trải nghiệm của khách lưu trú", "Guest experiences")}</h2>
          <p className="mt-2 text-sm text-white/72">{displayedTotal} {localize("lượt đánh giá cho hạng phòng này", displayedTotal === 1 ? "review for this room type" : "reviews for this room type")}</p>
        </header>

        <div className="grid min-h-0 flex-1 md:grid-cols-[15rem_minmax(0,1fr)]">
          <aside className="border-b border-[#0F2A43]/10 bg-[#F1F0EA] p-5 md:border-b-0 md:border-r sm:p-6">
            <p className="font-serif text-5xl font-bold text-[#0F2A43]">{Number(averageRating || 0).toFixed(1)}</p>
            <div className="mt-2 flex flex-nowrap gap-1 whitespace-nowrap text-lg leading-none text-[#B8944F]" aria-hidden="true">
              {Array.from({ length: 5 }).map((_, index) => <span key={index}>{index < Math.round(averageRating || 0) ? "★" : "☆"}</span>)}
            </div>
            <p className="mt-2 whitespace-nowrap text-sm font-semibold text-[#66727C]">{displayedTotal} {localize("đánh giá", displayedTotal === 1 ? "review" : "reviews")}</p>
            <div className="mt-5 rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-xs font-medium leading-5 text-emerald-800">
              {localize("Chỉ khách đã hoàn tất kỳ nghỉ mới có thể gửi đánh giá cho hạng phòng đã sử dụng.", "Only guests who completed their stay can review the room type they used.")}
            </div>
          </aside>

          <div className="flex min-h-0 flex-col bg-[#FBFAF6]">
            <div className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b border-[#0F2A43]/10 px-5 py-4 sm:px-6">
              <p className="text-sm font-bold text-[#0F2A43]">{localize("Tất cả đánh giá", "All reviews")}</p>
              <label className="flex items-center gap-2 text-xs font-bold text-[#66727C]">
                {localize("Sắp xếp", "Sort")}
                <select value={sort} onChange={(event) => changeSort(event.target.value as ReviewSort)} className="min-h-10 rounded-lg border border-[#0F2A43]/14 bg-white px-3 text-sm font-semibold text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20">
                  <option value="newest">{localize("Mới nhất", "Newest")}</option>
                  <option value="highest">{localize("Điểm cao", "Highest rated")}</option>
                  <option value="lowest">{localize("Điểm thấp", "Lowest rated")}</option>
                </select>
              </label>
            </div>

            <div className="lux-scrollbar min-h-0 flex-1 overflow-y-auto overscroll-contain p-4 sm:p-6">
              {isLoading ? (
                <div className="space-y-4">{Array.from({ length: 3 }).map((_, index) => <div key={index} className="skeleton-surface h-52 rounded-[1.25rem]" />)}</div>
              ) : hasInitialError ? (
                <div className="rounded-xl border border-amber-200 bg-amber-50 p-5 text-center"><p role="alert" className="text-sm font-semibold text-amber-900">{error}</p><button type="button" onClick={() => void loadPage(0, false, sort)} className="mt-4 min-h-11 rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white">{localize("Thử lại", "Try again")}</button></div>
              ) : (
                <div className="space-y-4">
                  {reviews.map((review, index) => <ReviewCard key={review.id || `${review.userName}-${index}`} review={review} detailed />)}
                  {error && (
                    <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-center">
                      <p role="alert" className="text-sm font-semibold text-amber-900">{error}</p>
                      <button type="button" onClick={() => void loadPage(reviewPage.page + 1, true, sort)} className="mt-3 min-h-10 rounded-lg bg-[#0F2A43] px-4 text-xs font-bold text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">{localize("Thử lại", "Try again")}</button>
                    </div>
                  )}
                  {reviewPage.hasNext && !error && (
                    <div className="flex justify-center pt-2">
                      <button type="button" disabled={isLoadingMore} onClick={() => void loadPage(reviewPage.page + 1, true, sort)} className="inline-flex min-h-12 min-w-36 items-center justify-center gap-2 rounded-xl border border-[#0F2A43]/16 bg-white px-6 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F1F0EA] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-60">
                        {isLoadingMore && <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-[#0F2A43]/25 border-r-[#0F2A43]" />}
                        {isLoadingMore ? localize("Đang tải...", "Loading...") : localize("Xem thêm", "View more")}
                      </button>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      </ViewportModal>
    </>
  );
}
