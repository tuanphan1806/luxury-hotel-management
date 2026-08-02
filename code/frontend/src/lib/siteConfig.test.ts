import { describe, expect, it } from "vitest";
import { resolveSiteUrl } from "./siteConfig";

describe("resolveSiteUrl", () => {
  it("prefers the explicitly configured public URL", () => {
    expect(resolveSiteUrl({
      configuredUrl: "https://hotel.example/",
      productionUrl: "luxury-hotel.vercel.app",
    })).toBe("https://hotel.example");
  });

  it("uses the Vercel production domain when NEXT_PUBLIC_SITE_URL is absent", () => {
    expect(resolveSiteUrl({ productionUrl: "luxury-hotel.vercel.app" }))
      .toBe("https://luxury-hotel.vercel.app");
  });

  it("does not accept an invalid deployment URL", () => {
    expect(resolveSiteUrl({ configuredUrl: "://invalid" }))
      .toBe("http://localhost:3000");
  });
});
