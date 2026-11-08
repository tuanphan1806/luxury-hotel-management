import { createRequire } from "node:module";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const sentry = createRequire(require.resolve("@sentry/nextjs"));
const bundler = createRequire(sentry.resolve("@sentry/bundler-plugins/core"));
const { glob } = bundler("glob") as {
  glob(pattern: string[], options: Record<string, unknown>): Promise<string[]>;
};
const nextLint = createRequire(require.resolve("eslint-config-next"));
const parser = createRequire(nextLint.resolve("@typescript-eslint/parser"));
const estree = createRequire(parser.resolve("@typescript-eslint/typescript-estree"));
const { minimatch } = estree("minimatch") as {
  minimatch(path: string, pattern: string, options: { dot: boolean }): boolean;
};

describe("patched build dependency compatibility", () => {
  it("preserves Sentry's absolute file discovery and ignore options", async () => {
    const files = await glob(["src/lib/*catalog*.ts"], {
      cwd: process.cwd(), absolute: true, nodir: true, ignore: ["**/*.test.ts"],
    });
    expect(files.map((file) => resolve(file))).toEqual([resolve("src/lib/public-catalog.ts")]);
  });

  it("honors an already aborted discovery request", async () => {
    const controller = new AbortController();
    controller.abort(new Error("Discovery canceled"));
    await expect(glob(["src/**/*.ts"], {
      cwd: process.cwd(), absolute: true, nodir: true, signal: controller.signal,
    })).rejects.toThrow("Discovery canceled");
  });

  it("preserves TypeScript ESLint default-project path matching", () => {
    expect(minimatch("eslint.config.ts", "*.{js,ts}", { dot: true })).toBe(true);
    expect(minimatch(".config/eslint.ts", "**/*.ts", { dot: true })).toBe(true);
    expect(minimatch("src/app/page.tsx", "*.ts", { dot: true })).toBe(false);
  });
});
