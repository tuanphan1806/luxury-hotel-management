import { getRoomPreviews } from "@/lib/room-catalog-server";
import RoomsCatalog from "./RoomsCatalog";

export const revalidate = 3600;

export default async function RoomsPage() {
  return <RoomsCatalog initialRooms={await getRoomPreviews()} />;
}
