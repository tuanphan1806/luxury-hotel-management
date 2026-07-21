import type { MetadataRoute } from "next";
import { absoluteSiteUrl } from "@/lib/siteConfig";

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    {
      url: absoluteSiteUrl("/"),
      changeFrequency: "weekly",
      priority: 1,
    },
    {
      url: absoluteSiteUrl("/rooms"),
      changeFrequency: "daily",
      priority: 0.9,
    },
    {
      url: absoluteSiteUrl("/facilities"),
      changeFrequency: "weekly",
      priority: 0.7,
    },
    {
      url: absoluteSiteUrl("/about"),
      changeFrequency: "monthly",
      priority: 0.7,
    },
    {
      url: absoluteSiteUrl("/support"),
      changeFrequency: "monthly",
      priority: 0.6,
    },
    {
      url: absoluteSiteUrl("/contact"),
      changeFrequency: "monthly",
      priority: 0.6,
    },
    {
      url: absoluteSiteUrl("/booking-policy"),
      changeFrequency: "monthly",
      priority: 0.5,
    },
    {
      url: absoluteSiteUrl("/cancellation-policy"),
      changeFrequency: "monthly",
      priority: 0.5,
    },
    {
      url: absoluteSiteUrl("/terms"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
    {
      url: absoluteSiteUrl("/privacy"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
    {
      url: absoluteSiteUrl("/data-deletion"),
      changeFrequency: "yearly",
      priority: 0.2,
    },
  ];
}
