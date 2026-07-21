"use client";

import Link from "next/link";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { GALLERY_HERO_IMAGES } from "@/constants/content";

export interface LocalizedCopy {
  vi: string;
  en: string;
}

export interface InformationSection {
  title: LocalizedCopy;
  body: LocalizedCopy;
  points?: LocalizedCopy[];
}

interface InformationPageProps {
  eyebrow: LocalizedCopy;
  title: LocalizedCopy;
  intro: LocalizedCopy;
  sections: InformationSection[];
  notice?: LocalizedCopy;
  primaryAction?: { href: string; label: LocalizedCopy };
  secondaryAction?: { href: string; label: LocalizedCopy };
}

export default function InformationPage({ eyebrow, title, intro, sections, notice, primaryAction, secondaryAction }: InformationPageProps) {
  const { localize } = useLanguage();
  const heroImage = GALLERY_HERO_IMAGES.information;

  return (
    <div className="min-h-screen bg-[#F1F0EA] pb-24 pt-20 text-[#0F2A43]">
      <header className="relative overflow-hidden border-b border-[#0F2A43]/12 bg-[#0F2A43] px-5 py-20 text-white md:px-8 md:py-24">
        <ProgressiveImage src={heroImage} alt={localize(title.vi, title.en)} fill priority quality={92} sizes="100vw" className="object-cover" loaderClassName="hero-image-loading-surface" />
        <div className="absolute inset-0 bg-gradient-to-r from-[#091E30]/72 via-[#0F2A43]/42 to-[#0F2A43]/10" />
        <div className="relative z-10 mx-auto grid max-w-6xl gap-8 lg:grid-cols-[minmax(0,1fr)_22rem] lg:items-end">
          <div>
            <p className="text-[10px] font-bold uppercase tracking-[0.22em] text-[#D8C398]">{localize(eyebrow.vi, eyebrow.en)}</p>
            <h1 className="mt-4 max-w-4xl font-serif text-4xl font-bold leading-tight md:text-6xl">{localize(title.vi, title.en)}</h1>
            <p className="mt-5 max-w-3xl text-sm font-medium leading-7 text-white/78 md:text-base">{localize(intro.vi, intro.en)}</p>
          </div>
          <div className="border-l border-[#B8944F] pl-5 text-xs leading-6 text-white/68">
            <p className="font-bold text-white">{localize("Thông tin dành cho khách đặt trực tiếp", "Information for direct-booking guests")}</p>
            <p className="mt-1">{localize("Các quy định dưới đây mô tả đúng luồng đang có trên website local/MVP.", "The guidance below reflects the current local/MVP website flow.")}</p>
          </div>
        </div>
      </header>

      <div className="mx-auto grid max-w-6xl gap-8 px-5 py-12 md:px-8 lg:grid-cols-[15rem_minmax(0,1fr)] lg:py-16">
        <aside className="h-fit rounded-xl border border-[#0F2A43]/12 bg-[#FBFAF6] p-5 lg:sticky lg:top-28">
          <p className="text-xs font-bold text-[#0F2A43]">{localize("Trong trang này", "On this page")}</p>
          <nav className="mt-4 grid gap-1" aria-label={localize("Mục lục", "Table of contents")}>
            {sections.map((section, index) => (
              <a key={section.title.vi} href={`#section-${index + 1}`} className="flex min-h-10 items-center gap-3 rounded-lg px-2 text-xs font-semibold text-[#66727C] transition hover:bg-[#F0EADF] hover:text-[#0F2A43]"><span className="text-[10px] tabular-nums text-[#80632F]">{String(index + 1).padStart(2, "0")}</span>{localize(section.title.vi, section.title.en)}</a>
            ))}
          </nav>
        </aside>

        <div className="space-y-4">
          {notice && <div className="rounded-xl border border-[#B8944F]/32 bg-[#F0EADF] p-5 text-sm leading-6 text-[#0F2A43]"><strong>{localize("Lưu ý: ", "Note: ")}</strong>{localize(notice.vi, notice.en)}</div>}
          {sections.map((section, index) => (
            <section key={section.title.vi} id={`section-${index + 1}`} className="scroll-mt-28 rounded-xl border border-[#0F2A43]/12 bg-[#FBFAF6] p-6 md:p-8">
              <div className="flex items-start gap-4"><span className="mt-1 text-[10px] font-bold tabular-nums tracking-[0.14em] text-[#80632F]">{String(index + 1).padStart(2, "0")}</span><div><h2 className="font-serif text-2xl font-bold md:text-3xl">{localize(section.title.vi, section.title.en)}</h2><p className="mt-3 max-w-3xl text-sm leading-7 text-[#66727C]">{localize(section.body.vi, section.body.en)}</p>{section.points && <ul className="mt-5 grid gap-3 text-sm leading-6 text-[#0F2A43]">{section.points.map((point) => <li key={point.vi} className="flex gap-3"><span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-[#B8944F]" /><span>{localize(point.vi, point.en)}</span></li>)}</ul>}</div></div>
            </section>
          ))}

          {(primaryAction || secondaryAction) && <div className="flex flex-wrap gap-3 pt-5">{primaryAction && <Link href={primaryAction.href} className="inline-flex min-h-12 items-center rounded-lg bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#091E30]">{localize(primaryAction.label.vi, primaryAction.label.en)}</Link>}{secondaryAction && <Link href={secondaryAction.href} className="inline-flex min-h-12 items-center rounded-lg border border-[#0F2A43]/18 bg-white px-6 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F0EADF]">{localize(secondaryAction.label.vi, secondaryAction.label.en)}</Link>}</div>}
        </div>
      </div>
    </div>
  );
}
