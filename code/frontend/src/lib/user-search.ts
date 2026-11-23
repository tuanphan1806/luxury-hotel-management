import { createSearchMatcher } from "./search-text";

export interface SearchableUser {
  fullName?: string | null;
  username?: string | null;
  email?: string | null;
  phone?: string | null;
  address?: string | null;
}

export function matchesUserSearch(user: SearchableUser, query: string): boolean {
  return createSearchMatcher(query)(user.fullName, user.username, user.email, user.phone, user.address);
}
