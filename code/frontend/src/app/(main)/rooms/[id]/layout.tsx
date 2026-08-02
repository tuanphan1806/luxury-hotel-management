import type { Metadata } from "next";

export const generateMetadata = async ({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<Metadata> => {
  const { id } = await params;
  const canonical = `/rooms/${encodeURIComponent(id)}`;
  return {
    alternates: { canonical },
    openGraph: { url: canonical },
  };
};

export default function RoomDetailLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
