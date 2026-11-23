export { normalizeSearchText as normalizeCatalogText } from "./search-text";

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
