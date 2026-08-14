import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

const PUBLIC_ROUTES = ['/', '/rooms', '/facilities', '/login', '/signup'];

test.describe('public accessibility smoke', () => {
  for (const route of PUBLIC_ROUTES) {
    test(`${route} has no serious or critical WCAG violations`, async ({ page }) => {
      const response = await page.goto(route, { waitUntil: 'domcontentloaded' });
      expect(response, `No navigation response for ${route}`).not.toBeNull();
      expect(response!.status(), `Unexpected HTTP status for ${route}`).toBeLessThan(500);
      await expect(page.locator('body')).toBeVisible();

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      const blockingViolations = results.violations.filter(
        (violation) => violation.impact === 'critical' || violation.impact === 'serious',
      );
      const diagnostics = blockingViolations.map((violation) => ({
        id: violation.id,
        impact: violation.impact,
        help: violation.help,
        nodes: violation.nodes.map((node) => node.target),
      }));

      expect(blockingViolations, JSON.stringify(diagnostics, null, 2)).toEqual([]);
    });
  }
});
