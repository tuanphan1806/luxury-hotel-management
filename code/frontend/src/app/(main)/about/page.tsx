"use client";

import Link from "next/link";
import GuestPageHero from "@/components/guest/GuestPageHero";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { FACILITIES_CONTENT, ROOMS_CONTENT } from "@/constants/content";

export default function AboutPage() {
  const { localize } = useLanguage();

  const principles = [
    {
      title: localize("Chỉn chu từ trước khi đến", "Prepared before arrival"),
      description: localize("Thông tin phòng, thời gian lưu trú, khoản thanh toán và bước tiếp theo được trình bày rõ ngay từ đầu.", "Room details, stay times, payments and the next step are made clear from the start."),
    },
    {
      title: localize("Minh bạch trong từng giao dịch", "Transparent in every transaction"),
      description: localize("Khách có thể chọn đặt cọc 50% hoặc thanh toán 100%, theo dõi trạng thái và tra cứu lại đơn khi cần.", "Guests can choose a 50% deposit or full payment, track status and retrieve their booking when needed."),
    },
    {
      title: localize("Con người vẫn là trung tâm", "People remain at the center"),
      description: localize("Công nghệ giúp giảm thao tác, còn đội ngũ khách sạn tập trung vào việc chuẩn bị phòng và hỗ trợ kỳ lưu trú.", "Technology reduces friction so the hotel team can focus on room readiness and stay support."),
    },
  ];

  const journey = [
    localize("Khám phá hạng phòng và tiện ích", "Explore rooms and facilities"),
    localize("Kiểm tra số phòng còn trống", "Check live availability"),
    localize("Đặt cọc hoặc thanh toán qua QR", "Pay a deposit or in full by QR"),
    localize("Theo dõi xác nhận, nhận phòng và trả phòng", "Track confirmation, check-in and checkout"),
  ];

  return (
    <div className="home-color-story">
      <GuestPageHero
        imageSrc="/hotel-lobby.png"
        imageAlt={localize("Sảnh chính của Luxury Hotel", "The main lobby at Luxury Hotel")}
        useGallery
        galleryKeywords={["toàn cảnh khách sạn 2", "hotel building 2"]}
        galleryIndex={0}
        eyebrow={localize("Về Luxury Hotel", "About Luxury Hotel")}
        title={localize("Sự sang trọng bắt đầu từ cảm giác được chuẩn bị chu đáo.", "Luxury begins with feeling thoughtfully prepared for.")}
        description={localize("Luxury Hotel kết nối không gian lưu trú chỉn chu với một hành trình đặt phòng trực tiếp, rõ ràng và dễ theo dõi.", "Luxury Hotel connects considered hospitality with a direct booking journey that is clear and easy to follow.")}
        actions={(
          <>
            <Link href="/reservation" className="inline-flex min-h-12 items-center justify-center rounded-lg bg-[#FBFAF6] px-6 text-sm font-bold text-[#0F2A43] transition hover:bg-white">{localize("Kiểm tra phòng trống", "Check availability")}</Link>
            <Link href="/rooms" className="inline-flex min-h-12 items-center justify-center rounded-lg border border-white/35 px-6 text-sm font-bold text-white transition hover:border-[#B8944F] hover:bg-white/6">{localize("Khám phá hạng phòng", "Explore rooms")}</Link>
          </>
        )}
      />

      <section className="mx-auto grid max-w-[1400px] gap-10 px-6 py-16 md:px-10 md:py-24 lg:grid-cols-[1.18fr_0.82fr] lg:items-center">
        <figure className="relative min-h-[28rem] overflow-hidden rounded-[2rem] bg-[#E5E9ED] md:min-h-[38rem]">
          <ProgressiveImage src={ROOMS_CONTENT.hero.bg} alt={localize("Không gian phòng nghỉ tại khách sạn", "A guest room at the hotel")} fill priority sizes="(min-width: 1024px) 58vw, 100vw" className="object-cover" />
          <figcaption className="absolute inset-x-5 bottom-5 rounded-xl bg-[#091E30]/92 p-4 text-sm font-medium leading-6 text-white md:inset-x-auto md:left-6 md:max-w-sm">
            {localize("Mỗi hạng phòng được trình bày cùng sức chứa, tiện ích và mức giá để khách dễ chọn đúng nhu cầu.", "Every room type shows capacity, facilities and price so guests can choose with confidence.")}
          </figcaption>
        </figure>

        <div className="lg:pl-6">
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Câu chuyện của chúng tôi", "Our story")}</p>
          <h2 className="mt-4 font-serif text-4xl font-bold leading-tight text-[#0F2A43] md:text-5xl">{localize("Một kỳ nghỉ tốt không nên bắt đầu bằng sự mơ hồ.", "A good stay should not begin with uncertainty.")}</h2>
          <div className="mt-7 space-y-5 text-sm font-medium leading-7 text-[#66727C] md:text-base">
            <p>{localize("Luxury Hotel được xây dựng quanh một nguyên tắc đơn giản: khách cần hiểu rõ mình đang chọn gì, thanh toán bao nhiêu và bước tiếp theo là gì.", "Luxury Hotel is built around a simple principle: guests should understand what they are choosing, what they are paying and what happens next.")}</p>
            <p>{localize("Từ danh mục phòng đến theo dõi thanh toán và hỗ trợ tại khách sạn, mỗi điểm chạm đều hướng tới sự bình tĩnh, chủ động và đáng tin cậy.", "From room discovery to payment tracking and hotel support, every touchpoint is designed to feel calm, considered and dependable.")}</p>
          </div>
          <Link href="/contact" className="mt-8 inline-flex min-h-11 items-center gap-2 border-b border-[#B8944F] text-sm font-bold text-[#0F2A43] transition hover:text-[#80632F]">{localize("Liên hệ đội ngũ khách sạn", "Contact the hotel team")} <span aria-hidden="true">→</span></Link>
        </div>
      </section>

      <section className="home-section-navy-soft px-6 py-16 md:px-10 md:py-20">
        <div className="mx-auto grid max-w-7xl gap-10 lg:grid-cols-[0.72fr_1.28fr]">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Cách chúng tôi phục vụ", "How we host")}</p>
            <h2 className="mt-4 font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">{localize("Ba nguyên tắc xuyên suốt kỳ lưu trú.", "Three principles throughout every stay.")}</h2>
          </div>
          <div className="border-t border-[#0F2A43]/14">
            {principles.map((principle, index) => (
              <article key={principle.title} className="grid gap-3 border-b border-[#0F2A43]/14 py-6 md:grid-cols-[4rem_1fr] md:py-8">
                <span className="font-serif text-2xl font-bold tabular-nums text-[#80632F]">0{index + 1}</span>
                <div><h3 className="text-lg font-bold text-[#0F2A43]">{principle.title}</h3><p className="mt-2 max-w-2xl text-sm font-medium leading-7 text-[#66727C]">{principle.description}</p></div>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="home-section-gold-soft px-6 py-16 md:px-10 md:py-20">
        <div className="mx-auto grid max-w-7xl gap-10 lg:grid-cols-[1fr_0.9fr] lg:items-center">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Hành trình liền mạch", "One connected journey")}</p>
            <h2 className="mt-4 max-w-3xl font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">{localize("Từ lúc tìm phòng đến khi hoàn tất kỳ lưu trú.", "From room search to the end of your stay.")}</h2>
            <ol className="mt-8 border-t border-[#0F2A43]/14">
              {journey.map((item, index) => <li key={item} className="flex min-h-16 items-center gap-5 border-b border-[#0F2A43]/14 py-4"><span className="font-serif text-xl font-bold tabular-nums text-[#80632F]">0{index + 1}</span><span className="text-sm font-bold text-[#0F2A43] md:text-base">{item}</span></li>)}
            </ol>
          </div>
          <figure className="relative min-h-[28rem] overflow-hidden rounded-[2rem] bg-[#E5E9ED]">
            <ProgressiveImage src={FACILITIES_CONTENT.hero.bg} alt={localize("Không gian nghỉ dưỡng và tiện ích", "Hotel facilities and leisure space")} fill sizes="(min-width: 1024px) 45vw, 100vw" className="object-cover" />
          </figure>
        </div>
      </section>

      <section className="home-section-navy-mist border-t border-[#0F2A43]/10 px-6 py-16 text-center md:px-10 md:py-20">
        <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Bắt đầu kỳ lưu trú", "Begin your stay")}</p>
        <h2 className="mx-auto mt-4 max-w-3xl font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">{localize("Chọn hạng phòng phù hợp và để chúng tôi chuẩn bị phần còn lại.", "Choose the right room and let us prepare the rest.")}</h2>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Link href="/reservation" className="inline-flex min-h-12 items-center justify-center rounded-lg bg-[#0F2A43] px-7 text-sm font-bold text-white transition hover:bg-[#091E30]">{localize("Đặt phòng trực tiếp", "Book direct")}</Link>
          <Link href="/facilities" className="inline-flex min-h-12 items-center justify-center rounded-lg border border-[#0F2A43]/18 bg-[#FBFAF6] px-7 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F]">{localize("Xem tiện ích", "View facilities")}</Link>
        </div>
      </section>
    </div>
  );
}
