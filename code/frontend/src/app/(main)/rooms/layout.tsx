import type { Metadata } from "next";

const title = "Phòng và hạng phòng";
const description = "Khám phá các hạng phòng, tiện nghi, sức chứa và mức giá tại Luxury Hotel.";

export const metadata: Metadata = {
  title,
  description,
  openGraph: {
    title,
    description,
  },
};

export default function RoomsRouteLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
