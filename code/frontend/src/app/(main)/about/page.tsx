"use client";

import Link from "next/link";
import GuestPageHero from "@/components/guest/GuestPageHero";
import AboutGalleryStack from "@/components/guest/AboutGalleryStack";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { FACILITIES_CONTENT } from "@/constants/content";

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
    localize("Khám phá hạng phòng và tiện nghi", "Explore rooms and facilities"),
    localize("Kiểm tra số phòng còn trống", "Check live availability"),
    localize("Đặt cọc hoặc thanh toán qua QR", "Pay a deposit or in full by QR"),
    localize("Theo dõi xác nhận, nhận phòng và trả phòng", "Track confirmation, check-in and checkout"),
  ];

  const proofPoints = [
    { value: "50% / 100%", label: localize("Chọn cách thanh toán", "Choose how to pay"), detail: localize("Đặt cọc hoặc thanh toán toàn bộ", "Deposit or pay in full") },
    { value: localize("Một đơn", "One booking"), label: localize("Nhiều hạng phòng", "Multiple room types"), detail: localize("Chọn đúng loại và số lượng cần thiết", "Choose the types and quantities you need") },
    { value: localize("Từng bước", "Every step"), label: localize("Trạng thái rõ ràng", "Clear status"), detail: localize("Từ thanh toán đến trả phòng", "From payment through checkout") },
  ];

  const experienceLayers = [
    {
      title: localize("Không gian có chủ đích", "Purposeful spaces"),
      description: localize("Phòng nghỉ và khu vực chung được giới thiệu bằng hình ảnh rõ ràng để khách hình dung đúng trước khi chọn.", "Rooms and shared spaces are shown clearly so guests can choose with the right expectations."),
    },
    {
      title: localize("Vận hành có thể theo dõi", "Trackable operations"),
      description: localize("Tình trạng đặt phòng, thanh toán và các bước trong kỳ lưu trú luôn có một điểm tra cứu thống nhất.", "Reservations, payments, and stay progress remain visible in one consistent place."),
    },
    {
      title: localize("Hỗ trợ đúng thời điểm", "Support at the right moment"),
      description: localize("Khi cần thay đổi hoặc cần trợ giúp tại khách sạn, yêu cầu được chuyển tới đúng bộ phận cùng thông tin liên quan.", "When plans change or help is needed on property, the request reaches the right team with the relevant context."),
    },
  ];

  return (
    <div className="home-color-story">
      <GuestPageHero
        imageSrc="/backend_proxy/galeries/about-courtyard-v2.webp"
        imageAlt={localize("Sân vườn và hồ nước ngập nắng tại Luxury Hotel", "The sunlit garden courtyard and pool at Luxury Hotel")}
        eyebrow={localize("Về Luxury Hotel", "About Luxury Hotel")}
        title={localize("Sự sang trọng bắt đầu từ cảm giác được chuẩn bị chu đáo.", "Luxury begins with feeling thoughtfully prepared for.")}
        description={localize("Luxury Hotel kết nối không gian lưu trú chỉn chu với một hành trình đặt phòng trực tiếp, rõ ràng và dễ theo dõi.", "Luxury Hotel connects considered hospitality with a direct booking journey that is clear and easy to follow.")}
        contentPosition="left"
        actions={(
          <>
            <Link href="/reservation" className="inline-flex min-h-12 items-center justify-center rounded-lg bg-[#FBFAF6] px-6 text-sm font-bold text-[#0F2A43] transition hover:bg-white">{localize("Kiểm tra phòng trống", "Check availability")}</Link>
            <Link href="/rooms" className="inline-flex min-h-12 items-center justify-center rounded-lg border border-white/35 px-6 text-sm font-bold text-white transition hover:border-[#B8944F] hover:bg-white/6">{localize("Khám phá hạng phòng", "Explore rooms")}</Link>
          </>
        )}
      />

      <section className="deferred-section border-b border-[#0F2A43]/10 bg-[#FBFAF6] px-6 py-10 md:px-10 md:py-12" aria-label={localize("Điểm nổi bật của hành trình đặt phòng", "Booking journey highlights")}>
        <dl className="mx-auto grid max-w-7xl gap-px overflow-hidden rounded-[1.5rem] border border-[#0F2A43]/10 bg-[#0F2A43]/10 md:grid-cols-3">
          {proofPoints.map((point, index) => (
            <div key={point.label} className={`p-6 md:p-8 ${index === 1 ? "bg-[#EAE2D2]" : "bg-[#F1F0EA]"}`}>
              <dt className="font-serif text-3xl font-bold text-[#0F2A43]">{point.value}</dt>
              <dd className="mt-3 text-sm font-bold text-[#0F2A43]">{point.label}</dd>
              <p className="mt-2 text-xs font-medium leading-5 text-[#66727C]">{point.detail}</p>
            </div>
          ))}
        </dl>
      </section>

      <section className="deferred-section mx-auto grid max-w-[1400px] gap-12 px-6 py-16 md:px-10 md:py-24 lg:grid-cols-[0.8fr_1.2fr] lg:items-center lg:gap-16">
        <div className="lg:pr-2">
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Câu chuyện của chúng tôi", "Our story")}</p>
          <h2 className="mt-4 font-serif text-4xl font-bold leading-tight text-[#0F2A43] md:text-5xl">{localize("Một kỳ nghỉ tốt không nên bắt đầu bằng sự mơ hồ.", "A good stay should not begin with uncertainty.")}</h2>
          <div className="mt-7 space-y-5 text-sm font-medium leading-7 text-[#66727C] md:text-base">
            <p>{localize("Luxury Hotel được xây dựng quanh một nguyên tắc đơn giản: khách cần hiểu rõ mình đang chọn gì, thanh toán bao nhiêu và bước tiếp theo là gì.", "Luxury Hotel is built around a simple principle: guests should understand what they are choosing, what they are paying and what happens next.")}</p>
            <p>{localize("Từ danh mục phòng đến theo dõi thanh toán và hỗ trợ tại khách sạn, mỗi điểm chạm đều hướng tới sự bình tĩnh, chủ động và đáng tin cậy.", "From room discovery to payment tracking and hotel support, every touchpoint is designed to feel calm, considered and dependable.")}</p>
          </div>
          <div className="mt-8 border-l-2 border-[#B8944F] bg-[#EAE2D2]/55 px-5 py-4">
            <p className="font-serif text-xl font-semibold leading-8 text-[#0F2A43]">{localize("Một trải nghiệm cao cấp không cần khiến khách phải đoán bước tiếp theo.", "A premium experience should never leave a guest guessing what comes next.")}</p>
          </div>
          <Link href="/contact" className="mt-8 inline-flex min-h-11 items-center gap-2 border-b border-[#B8944F] text-sm font-bold text-[#0F2A43] transition hover:text-[#80632F]">{localize("Liên hệ đội ngũ khách sạn", "Contact the hotel team")} <span aria-hidden="true">→</span></Link>
        </div>

        <AboutGalleryStack />
      </section>

      <section className="home-section-ivory deferred-section px-6 py-16 md:px-10 md:py-24">
        <div className="mx-auto grid max-w-[1400px] gap-12 lg:grid-cols-[1.08fr_0.92fr] lg:items-center">
          <div className="about-collage grid min-h-[32rem] grid-cols-12 grid-rows-12 gap-3 md:min-h-[38rem] md:gap-4">
            <figure className="about-collage-card about-collage-card-top relative col-span-5 row-span-6 overflow-hidden rounded-[1.5rem] bg-[#E5E9ED]">
              <ProgressiveImage src="/backend_proxy/galeries/g-8.jpg?v=20260722-about" alt={localize("Sảnh xanh và chi tiết kiến trúc tại Luxury Hotel", "Green lobby and architectural details at Luxury Hotel")} fill sizes="(min-width: 1024px) 19vw, 42vw" className="object-cover" />
            </figure>
            <figure className="about-collage-card about-collage-card-main relative col-span-7 col-start-6 row-span-12 overflow-hidden rounded-[2rem] bg-[#E5E9ED]">
              <ProgressiveImage src={FACILITIES_CONTENT.hero.bg} alt={localize("Không gian tiện nghi và hồ bơi của khách sạn", "Hotel facilities and pool space")} fill sizes="(min-width: 1024px) 29vw, 56vw" className="object-cover" />
            </figure>
            <figure className="about-collage-card about-collage-card-bottom relative col-span-5 row-span-6 row-start-7 overflow-hidden rounded-[1.5rem] bg-[#E5E9ED]">
              <ProgressiveImage src="/backend_proxy/galeries/g-11.jpg?v=20260722-about" alt={localize("Không gian thư giãn giàu ánh sáng tại Luxury Hotel", "A bright relaxation space at Luxury Hotel")} fill sizes="(min-width: 1024px) 19vw, 42vw" className="object-cover" />
            </figure>
          </div>

          <div>
            <p className="text-xs font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Trải nghiệm liền mạch", "A connected experience")}</p>
            <h2 className="mt-4 font-serif text-4xl font-bold leading-tight text-[#0F2A43] md:text-5xl">{localize("Không chỉ đẹp khi nhìn, mà còn dễ dàng khi sử dụng.", "Designed not only to look beautiful, but to feel effortless.")}</h2>
            <div className="mt-8 border-t border-[#0F2A43]/14">
              {experienceLayers.map((layer, index) => (
                <article key={layer.title} className="grid gap-3 border-b border-[#0F2A43]/14 py-6 sm:grid-cols-[3rem_1fr]">
                  <span className="font-serif text-xl font-bold tabular-nums text-[#80632F]">0{index + 1}</span>
                  <div>
                    <h3 className="text-base font-bold text-[#0F2A43]">{layer.title}</h3>
                    <p className="mt-2 text-sm font-medium leading-7 text-[#66727C]">{layer.description}</p>
                  </div>
                </article>
              ))}
            </div>
          </div>
        </div>
      </section>

      <section className="home-section-navy-soft deferred-section px-6 py-16 md:px-10 md:py-20">
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

      <section className="home-section-gold-soft deferred-section px-6 py-16 md:px-10 md:py-20">
        <div className="mx-auto grid max-w-7xl gap-10 lg:grid-cols-[1fr_0.9fr] lg:items-center">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Hành trình liền mạch", "One connected journey")}</p>
            <h2 className="mt-4 max-w-3xl font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">{localize("Từ lúc tìm phòng đến khi hoàn tất kỳ lưu trú.", "From room search to the end of your stay.")}</h2>
            <ol className="mt-8 border-t border-[#0F2A43]/14">
              {journey.map((item, index) => <li key={item} className="flex min-h-16 items-center gap-5 border-b border-[#0F2A43]/14 py-4"><span className="font-serif text-xl font-bold tabular-nums text-[#80632F]">0{index + 1}</span><span className="text-sm font-bold text-[#0F2A43] md:text-base">{item}</span></li>)}
            </ol>
          </div>
          <figure className="guest-media-lift relative min-h-[28rem] overflow-hidden rounded-[2rem] bg-[#E5E9ED]">
            <ProgressiveImage src="/backend_proxy/galeries/g-12.jpg?v=20260722-about" alt={localize("Không gian nghỉ dưỡng và tiện nghi", "Hotel facilities and leisure space")} fill sizes="(min-width: 1024px) 45vw, 100vw" className="object-cover" />
          </figure>
        </div>
      </section>

      <section className="home-section-navy-mist deferred-section border-t border-[#0F2A43]/10 px-6 py-16 text-center md:px-10 md:py-20">
        <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Bắt đầu kỳ lưu trú", "Begin your stay")}</p>
        <h2 className="mx-auto mt-4 max-w-3xl font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">{localize("Chọn hạng phòng phù hợp và để chúng tôi chuẩn bị phần còn lại.", "Choose the right room and let us prepare the rest.")}</h2>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Link href="/reservation" className="inline-flex min-h-12 items-center justify-center rounded-lg bg-[#0F2A43] px-7 text-sm font-bold text-white transition hover:bg-[#091E30]">{localize("Đặt phòng trực tiếp", "Book direct")}</Link>
          <Link href="/facilities" className="inline-flex min-h-12 items-center justify-center rounded-lg border border-[#0F2A43]/18 bg-[#FBFAF6] px-7 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F]">{localize("Xem tiện nghi", "View facilities")}</Link>
        </div>
      </section>
    </div>
  );
}
