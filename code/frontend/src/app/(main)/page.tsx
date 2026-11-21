import { getRoomPreviews } from "@/lib/room-catalog-server";
import HomeContent from "./HomeContent";

export const revalidate = 3600;

export default async function HomePage() {
  return <HomeContent initialRooms={await getRoomPreviews()} />;
}
