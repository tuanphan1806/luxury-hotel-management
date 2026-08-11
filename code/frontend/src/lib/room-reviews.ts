export interface PublicRoomReview {
  id?: number;
  userName?: string;
  userImageUrl?: string;
  rating?: number;
  comment?: string;
  createdAt?: string;
  verifiedStay?: boolean;
}

export interface PublicRoomReviewPage {
  content: PublicRoomReview[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export const emptyRoomReviewPage = (size = 6): PublicRoomReviewPage => ({
  content: [],
  page: 0,
  size,
  totalElements: 0,
  totalPages: 0,
  hasNext: false,
});

export function parseRoomReviewPage(payload: unknown, fallbackSize = 6): PublicRoomReviewPage {
  if (!payload || typeof payload !== "object") return emptyRoomReviewPage(fallbackSize);
  const root = payload as Record<string, unknown>;
  const data = root.data && typeof root.data === "object"
    ? root.data as Record<string, unknown>
    : root;
  const content = Array.isArray(data.content) ? data.content as PublicRoomReview[] : [];
  return {
    content,
    page: Number(data.page || 0),
    size: Number(data.size || fallbackSize),
    totalElements: Number(data.totalElements || content.length),
    totalPages: Number(data.totalPages || (content.length ? 1 : 0)),
    hasNext: Boolean(data.hasNext),
  };
}

export function mergeRoomReviews(
  current: PublicRoomReview[],
  next: PublicRoomReview[],
): PublicRoomReview[] {
  const merged = new Map<string, PublicRoomReview>();
  [...current, ...next].forEach((review, index) => {
    merged.set(String(review.id ?? `${review.userName}-${review.createdAt}-${index}`), review);
  });
  return Array.from(merged.values());
}
