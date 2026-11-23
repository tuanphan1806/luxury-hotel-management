type SearchValue = string | number | null | undefined;

export function normalizeSearchText(value: SearchValue): string {
  return String(value ?? "")
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/đ/g, "d")
    .trim()
    .replace(/\s+/g, " ");
}

export function createSearchMatcher(query: string) {
  const needle = normalizeSearchText(query);
  return (...fields: SearchValue[]) =>
    !needle || fields.some((field) => normalizeSearchText(field).includes(needle));
}
