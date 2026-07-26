import { expect, test } from '@playwright/test';

test.describe('public customer experience', () => {
  test('critical public routes render without server or browser errors', async ({ page }) => {
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(error.message));

    for (const path of [
      '/',
      '/rooms',
      '/facilities',
      '/about',
      '/reservation',
      '/support',
      '/login',
      '/signup',
    ]) {
      const response = await page.goto(path, { waitUntil: 'domcontentloaded' });
      expect(response, `No navigation response for ${path}`).not.toBeNull();
      expect(response!.status(), `Unexpected HTTP status for ${path}`).toBeLessThan(500);
      await expect(page.locator('body')).toBeVisible();
      await expect(page.locator('body')).not.toBeEmpty();
    }

    expect(pageErrors).toEqual([]);
  });

  test('public catalogue APIs return usable payloads through the frontend proxy', async ({ request }) => {
    for (const endpoint of [
      '/backend_proxy/api/room-types?page=0&size=20',
      '/backend_proxy/api/facilities?page=0&size=20',
      '/backend_proxy/api/galleries?page=0&size=20',
      '/backend_proxy/auth/oauth/providers',
    ]) {
      const response = await request.get(endpoint);
      expect(response.status(), endpoint).toBe(200);
      const payload = await response.json();
      expect(payload, endpoint).toBeTruthy();
    }
  });

  test('login form reports both missing fields inline and focuses the first field', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: /^Đăng nhập$/ }).click();

    await expect(page.getByText('Vui lòng nhập tên đăng nhập hoặc email.')).toBeVisible();
    await expect(page.getByText('Vui lòng nhập mật khẩu.')).toBeVisible();
    await expect(page.locator('#login-email')).toBeFocused();
  });

  test('anonymous dashboard access redirects to login', async ({ page }) => {
    await page.goto('/dashboard');
    await expect(page).toHaveURL(/\/login(?:[?#].*)?$/);
  });
});
