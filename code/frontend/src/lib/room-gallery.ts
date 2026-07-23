export const normalizeCatalogText = (value?: string) =>
  (value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/đ/g, "d")
    .toLowerCase();

export const getRoomGalleryImages = (typeName?: string, typeNameEn?: string, fallbackImage?: string) => {
  const normalizedName = normalizeCatalogText(`${typeName || ""} ${typeNameEn || ""}`);
  let gallery: string[] = [];

  if (normalizedName.includes("executive")) {
    gallery = [
      "/backend_proxy/room_types/room-executive-main.webp",
      "/backend_proxy/room_types/room-executive-work.webp",
      "/backend_proxy/room_types/room-executive-bathroom.webp",
    ];
  } else if (normalizedName.includes("tong thong") || normalizedName.includes("presidential")) {
    gallery = [
      "/backend_proxy/room_types/room-presidential-detail.webp",
      "/backend_proxy/room_types/13.jpg",
      "/backend_proxy/room_types/14.jpg",
    ];
  } else if (normalizedName.includes("gia dinh") || normalizedName.includes("family")) {
    gallery = [
      "/backend_proxy/room_types/room-family-detail.webp",
      "/backend_proxy/room_types/11.jpg",
      "/backend_proxy/room_types/10.jpg",
    ];
  } else if (normalizedName.includes("suite")) {
    gallery = [
      "/backend_proxy/room_types/room-suite-detail.webp",
      "/backend_proxy/room_types/12.jpg",
      "/backend_proxy/room_types/5.jpg",
    ];
  } else if (normalizedName.includes("deluxe")) {
    gallery = [
      "/backend_proxy/room_types/room-deluxe-detail.webp",
      "/backend_proxy/room_types/9.jpg",
      "/backend_proxy/room_types/8.jpg",
    ];
  } else if (normalizedName.includes("tieu chuan") || normalizedName.includes("standard")) {
    gallery = [
      "/backend_proxy/room_types/room-standard-main.webp",
      "/backend_proxy/room_types/7.jpg",
      "/backend_proxy/room_types/room-standard-detail.webp",
    ];
  }

  return gallery.length ? gallery : [fallbackImage].filter((image): image is string => Boolean(image));
};
