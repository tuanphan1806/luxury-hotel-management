export const normalizeCatalogText = (value?: string) =>
  (value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/đ/g, "d")
    .toLowerCase();

export const getRoomGalleryImages = (
  _typeName?: string,
  _typeNameEn?: string,
  fallbackImage?: string,
  imageUrls: string[] = [],
) => {
  const persistedGallery = Array.from(
    new Set(imageUrls.map((image) => image?.trim()).filter((image): image is string => Boolean(image))),
  ).slice(0, 3);
  return persistedGallery.length
    ? persistedGallery
    : [fallbackImage?.trim()].filter((image): image is string => Boolean(image));
};
