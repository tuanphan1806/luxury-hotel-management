const DEFAULT_SITE_URL = "http://localhost:3000";

const trimTrailingSlash = (value: string) => value.replace(/\/+$/, "");

const getSiteUrl = () => {
  const configuredUrl = process.env.NEXT_PUBLIC_SITE_URL?.trim();
  if (!configuredUrl) return DEFAULT_SITE_URL;

  try {
    return trimTrailingSlash(new URL(configuredUrl).toString());
  } catch {
    return DEFAULT_SITE_URL;
  }
};

const optionalEnvironmentValue = (value?: string) => value?.trim() || undefined;

export const siteConfig = {
  name: optionalEnvironmentValue(process.env.HOTEL_NAME) || "Luxury Hotel",
  description:
    "Tìm phòng, đặt cọc và theo dõi kỳ nghỉ tại Luxury Hotel trong một quy trình rõ ràng.",
  descriptionEn:
    "Find rooms, pay a deposit, and follow your stay at Luxury Hotel through one clear booking journey.",
  url: getSiteUrl(),
  image: "/hotel-lobby.png",
  contact: {
    address: optionalEnvironmentValue(process.env.HOTEL_ADDRESS),
    phone: optionalEnvironmentValue(process.env.HOTEL_PHONE),
    email: optionalEnvironmentValue(process.env.HOTEL_EMAIL),
  },
} as const;

export const absoluteSiteUrl = (path: string) => new URL(path, `${siteConfig.url}/`).toString();
