import type { ImageProps } from "next/image";

export type MediaSource = ImageProps["src"];

/**
 * Seeded/local media URLs are public from the host browser, but `localhost`
 * inside a frontend container points back to that container. Route those URLs
 * through the existing same-origin proxy so Next's image optimizer can reach
 * the backend by its Compose service name.
 */
export function resolveMediaSource(source: MediaSource): MediaSource {
  if (typeof source !== "string" || !source.startsWith("http")) {
    return source;
  }

  try {
    const url = new URL(source);
    const localBackend = ["localhost", "127.0.0.1", "::1"].includes(url.hostname)
      && url.port === "8080";
    const composeBackend = url.hostname === "backend" && url.port === "8080";

    if (localBackend || composeBackend) {
      return `/backend_proxy${url.pathname}${url.search}`;
    }
  } catch {
    // Keep the original value; Next.js will surface invalid image URLs.
  }

  return source;
}
