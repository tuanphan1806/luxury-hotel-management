import type { MetadataRoute } from "next";
import { absoluteSiteUrl } from "@/lib/siteConfig";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      allow: ["/", "/rooms", "/facilities"],
      disallow: [
        "/dashboard",
        "/account",
        "/my-bookings",
        "/booking/",
        "/reservation",
        "/login",
        "/signup",
        "/forgot-password",
        "/reset-password",
        "/resend-verification",
        "/oauth/",
      ],
    },
    sitemap: absoluteSiteUrl("/sitemap.xml"),
  };
}
