import { getRequestConfig } from "next-intl/server";
import { defaultLocale } from "./config";
import viMessages from "./messages/vi.json";

export default getRequestConfig(async () => {
  return {
    locale: defaultLocale,
    messages: viMessages,
  };
});
