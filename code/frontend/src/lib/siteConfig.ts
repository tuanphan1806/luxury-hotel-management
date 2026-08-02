const DEFAULT_SITE_URL = "http://localhost:3000";

const trimTrailingSlash = (value: string) => value.replace(/\/+$/, "");

const normalizeDeploymentUrl = (value?: string) => {
  const candidate = value?.trim();
  if (!candidate) return undefined;
  const withProtocol = /^https?:\/\//i.test(candidate) ? candidate : `https://${candidate}`;

  try {
    const url = new URL(withProtocol);
    if (!['http:', 'https:'].includes(url.protocol)) return undefined;
    return trimTrailingSlash(url.toString());
  } catch {
    return undefined;
  }
};

export const resolveSiteUrl = ({
  configuredUrl,
  productionUrl,
  deploymentUrl,
}: {
  configuredUrl?: string;
  productionUrl?: string;
  deploymentUrl?: string;
}) => normalizeDeploymentUrl(configuredUrl)
  ?? normalizeDeploymentUrl(productionUrl)
  ?? normalizeDeploymentUrl(deploymentUrl)
  ?? DEFAULT_SITE_URL;

const getSiteUrl = () => resolveSiteUrl({
  configuredUrl: process.env.NEXT_PUBLIC_SITE_URL,
  productionUrl: process.env.VERCEL_PROJECT_PRODUCTION_URL,
  deploymentUrl: process.env.VERCEL_URL,
});

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
