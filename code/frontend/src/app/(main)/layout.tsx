import type { Metadata } from "next";
import MainSiteShell from "@/components/layout/MainSiteShell";
import { absoluteSiteUrl, siteConfig } from "@/lib/siteConfig";

export const metadata: Metadata = {
  title: {
    default: "Đặt phòng trực tuyến",
    template: `%s | ${siteConfig.name}`,
  },
  description: siteConfig.description,
};

const structuredData = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "WebSite",
      "@id": `${siteConfig.url}/#website`,
      name: siteConfig.name,
      url: siteConfig.url,
      description: siteConfig.description,
      inLanguage: ["vi", "en"],
    },
    {
      "@type": "Hotel",
      "@id": `${siteConfig.url}/#hotel`,
      name: siteConfig.name,
      url: siteConfig.url,
      description: siteConfig.description,
      image: absoluteSiteUrl(siteConfig.image),
      logo: absoluteSiteUrl("/favicon.png"),
      ...(siteConfig.contact.address ? { address: siteConfig.contact.address } : {}),
      ...(siteConfig.contact.phone ? { telephone: siteConfig.contact.phone } : {}),
      ...(siteConfig.contact.email ? { email: siteConfig.contact.email } : {}),
    },
  ],
};

export default function MainLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify(structuredData).replace(/</g, "\\u003c"),
        }}
      />
      <MainSiteShell>{children}</MainSiteShell>
    </>
  );
}
