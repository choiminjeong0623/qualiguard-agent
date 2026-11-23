// 리디자인 전후 비교용 화면 사진. 판정 없이 docs/ui-redesign/<라벨>/ 에 저장한다.
// 실행: QG_CAPTURE_LABEL=before npx playwright test --project=capture
const path = require('path');
const { test } = require('@playwright/test');
const { open, cdn } = require('./helpers');

const LABEL = process.env.QG_CAPTURE_LABEL || 'current';
const OUT = path.join(__dirname, '..', '..', 'docs', 'ui-redesign', LABEL);
const SIZES = [{ name: 'desktop', width: 1440, height: 900 }, { name: 'mobile', width: 390, height: 844 }];

for (const size of SIZES) {
  test.describe(size.name, () => {
    test.use({ viewport: { width: size.width, height: size.height } });

    test('부적합 검토 - 목록', async ({ page }) => {
      await open(page, '/qms/index.html');
      await page.getByRole('row', { name: /AI-2026-0110/ }).waitFor();
      await page.waitForTimeout(300);
      await page.screenshot({ path: path.join(OUT, `${size.name}-ncr-list.png`), fullPage: true });
    });
    test('부적합 검토 - 근거 탭', async ({ page }) => {
      await open(page, '/qms/index.html');
      await page.getByRole('row', { name: /AI-2026-0110/ }).click();
      const tab = page.getByRole('tab', { name: /근거/ });
      if (await tab.count()) { await tab.click(); }
      await page.waitForTimeout(300);
      await page.screenshot({ path: path.join(OUT, `${size.name}-ncr-evidence.png`), fullPage: true });
    });
    test('부적합 검토 - 초안', async ({ page }) => {
      await open(page, '/qms/index.html');
      await page.getByRole('row', { name: /AI-2026-0110/ }).click();
      await page.waitForTimeout(300);
      await page.screenshot({ path: path.join(OUT, `${size.name}-ncr-draft.png`), fullPage: true });
    });
    test('부적합 검토 - 승인', async ({ page }) => {
      await open(page, '/qms/index.html');
      await page.getByRole('tab', { name: /승인\s*1/ }).click();
      await page.getByRole('row', { name: /AI-2026-0036/ }).click();
      await page.waitForTimeout(300);
      await page.screenshot({ path: path.join(OUT, `${size.name}-ncr-approved.png`), fullPage: true });
    });
    test('에이전트 조치', async ({ page }) => {
      await open(page, '/qms/actions.html');
      await page.getByText('라인 정지·체크 링 교체').waitFor();
      await page.screenshot({ path: path.join(OUT, `${size.name}-actions.png`), fullPage: true });
    });
    test('출하 시리얼', async ({ page }) => {
      await open(page, '/qms/shipment.html');
      await page.getByText('SN-2026-000120').waitFor();
      await page.screenshot({ path: path.join(OUT, `${size.name}-shipment.png`), fullPage: true });
    });
    test('로그인', async ({ page }) => {
      await cdn(page);
      await page.goto('/qms/login.html');
      await page.screenshot({ path: path.join(OUT, `${size.name}-login.png`), fullPage: true });
    });
  });
}
