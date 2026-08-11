import { describe, expect, it } from "vitest";
import { mergeRoomReviews, parseRoomReviewPage } from "./room-reviews";

describe("room review pagination helpers", () => {
  it("parses the standard API envelope", () => {
    const page = parseRoomReviewPage({
      success: true,
      data: {
        content: [{ id: 1, rating: 5 }],
        page: 2,
        size: 6,
        totalElements: 50,
        totalPages: 9,
        hasNext: true,
      },
    });

    expect(page.content).toHaveLength(1);
    expect(page.page).toBe(2);
    expect(page.totalElements).toBe(50);
    expect(page.hasNext).toBe(true);
  });

  it("merges pages without duplicating reviews", () => {
    expect(mergeRoomReviews(
      [{ id: 1, rating: 4 }, { id: 2, rating: 5 }],
      [{ id: 2, rating: 5 }, { id: 3, rating: 3 }],
    ).map((review) => review.id)).toEqual([1, 2, 3]);
  });
});
