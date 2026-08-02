import type { Metadata } from "next";
import { Be_Vietnam_Pro, Playfair_Display } from "next/font/google";
import "../index.css";
import { LanguageProvider } from "@/components/i18n/LanguageProvider";
import { FavoritesProvider } from "@/components/favorites/FavoritesProvider";
import { AccessibilityProvider } from "@/components/accessibility/AccessibilityProvider";
import BackToTopButton from "@/components/UI/BackToTopButton";
import { siteConfig } from "@/lib/siteConfig";
import viMessages from "@/i18n/messages/vi.json";

const beVietnam = Be_Vietnam_Pro({
  subsets: ["latin", "vietnamese"],
  weight: ["400", "600", "700"],
  variable: "--font-be-vietnam",
  display: "swap",
});

const playfair = Playfair_Display({
  subsets: ["latin", "vietnamese"],
  variable: "--font-playfair",
  display: "swap",
});

export const metadata: Metadata = {
  metadataBase: new URL(siteConfig.url),
  title: {
    default: `${siteConfig.name} | Đặt phòng trực tuyến`,
    template: `%s | ${siteConfig.name}`,
  },
  description: siteConfig.description,
  applicationName: siteConfig.name,
  alternates: {
    canonical: "/",
  },
  keywords: ["khách sạn", "đặt phòng", "Luxury Hotel", "SePay", "VietQR", "hotel booking"],
  icons: {
    icon: "/favicon.png",
  },
  openGraph: {
    type: "website",
    locale: "vi_VN",
    alternateLocale: ["en_US"],
    siteName: siteConfig.name,
    title: `${siteConfig.name} | Đặt phòng trực tuyến`,
    description: siteConfig.description,
    url: "/",
    images: [{ url: siteConfig.image, width: 1024, height: 1024, alt: siteConfig.name }],
  },
  twitter: {
    card: "summary_large_image",
    title: `${siteConfig.name} | Đặt phòng trực tuyến`,
    description: siteConfig.description,
    images: [siteConfig.image],
  },
  robots: {
    index: true,
    follow: true,
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="vi" className="scroll-smooth" data-scroll-behavior="smooth">
      <body suppressHydrationWarning className={`${beVietnam.variable} ${playfair.variable} bg-[#F1F0EA] font-sans text-text-dark antialiased`}>
        <AccessibilityProvider>
          <LanguageProvider initialLocale="vi" messages={viMessages}>
            <FavoritesProvider>
              {children}
              <BackToTopButton />
            </FavoritesProvider>
          </LanguageProvider>
        </AccessibilityProvider>
      </body>
    </html>
  );
}
