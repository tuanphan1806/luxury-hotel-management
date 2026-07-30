export interface SearchableUser {
  fullName?: string | null;
  username?: string | null;
  email?: string | null;
  phone?: string | null;
  address?: string | null;
}

export function matchesUserSearch(user: SearchableUser, query: string): boolean {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  if (!normalizedQuery) return true;

  return [user.fullName, user.username, user.email, user.phone, user.address].some(
    (value) => typeof value === "string"
      && value.toLocaleLowerCase().includes(normalizedQuery),
  );
}
