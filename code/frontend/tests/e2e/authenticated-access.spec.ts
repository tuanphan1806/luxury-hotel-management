import { expect, test, type Page } from "@playwright/test";

const DEMO_PASSWORD = "123456";

async function loginThroughUi(page: Page, username: string) {
  await page.goto("/login");
  await page.locator("#login-email").fill(username);
  await page.locator("#login-password").fill(DEMO_PASSWORD);

  const loginResponsePromise = page.waitForResponse((response) => (
    response.request().method() === "POST"
    && response.url().includes("/backend_proxy/auth/login")
  ));
  await page.getByRole("button", { name: /^Đăng nhập$/ }).click();
  const loginResponse = await loginResponsePromise;
  expect(loginResponse.status()).toBe(200);

  const payload = await loginResponse.json() as { accessToken?: string };
  expect(payload.accessToken).toBeTruthy();
  return payload.accessToken!;
}

test.describe("authenticated role and session boundaries", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeEach(({}, testInfo) => {
    test.skip(testInfo.project.name !== "desktop-chrome", "Stateful account tests run once to avoid invalidating single-device staff sessions.");
  });

  test("ADMIN reaches operations pages and the ADMIN-only audit API", async ({ page }) => {
    const token = await loginThroughUi(page, "admin");
    await expect(page).toHaveURL(/\/dashboard$/);
    await expect(page.getByRole("link", { name: "Nhật ký hệ thống" })).toBeVisible();

    const auditResponse = await page.request.get(
      "/backend_proxy/api/admin/audit-logs?page=0&size=5",
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(auditResponse.status()).toBe(200);

    await page.getByRole("link", { name: "Nhật ký hệ thống" }).click();
    await expect(page).toHaveURL(/\/dashboard\/audit-logs$/);
    await expect(page.getByRole("heading", { name: "Dòng thời gian thao tác" })).toBeVisible();
  });

  test("STAFF keeps operational access but cannot see or call audit history", async ({ page }) => {
    const token = await loginThroughUi(page, "staff1");
    await expect(page).toHaveURL(/\/dashboard$/);
    await expect(page.getByRole("link", { name: "Đặt phòng", exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "Nhật ký hệ thống" })).toHaveCount(0);

    const auditResponse = await page.request.get(
      "/backend_proxy/api/admin/audit-logs?page=0&size=5",
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(auditResponse.status()).toBe(403);
  });

  test("CUSTOMER returns to the hotel site and cannot remain in the dashboard", async ({ page }) => {
    await loginThroughUi(page, "customer1");
    await expect(page).toHaveURL(/\/$/);

    await page.goto("/dashboard");
    // The first local-dev visit may cold-compile /account before the client
    // redirect settles. Keep the assertion strict while allowing that compile.
    await expect(page).toHaveURL(/\/account$/, { timeout: 15_000 });
  });

  test("a second ADMIN login invalidates the first device token", async ({ request }) => {
    const firstLogin = await request.post("/backend_proxy/auth/login", {
      data: { username: "admin", password: DEMO_PASSWORD },
    });
    expect(firstLogin.status()).toBe(200);
    const firstToken = (await firstLogin.json() as { accessToken: string }).accessToken;

    const secondLogin = await request.post("/backend_proxy/auth/login", {
      data: { username: "admin", password: DEMO_PASSWORD },
    });
    expect(secondLogin.status()).toBe(200);
    const secondToken = (await secondLogin.json() as { accessToken: string }).accessToken;

    const staleSession = await request.get("/backend_proxy/api/user/me", {
      headers: { Authorization: `Bearer ${firstToken}` },
    });
    const currentSession = await request.get("/backend_proxy/api/user/me", {
      headers: { Authorization: `Bearer ${secondToken}` },
    });

    expect(staleSession.status()).toBe(401);
    expect(currentSession.status()).toBe(200);
  });
});
