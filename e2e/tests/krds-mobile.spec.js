// KRDS 디지털 취약계층 기준 모바일 화면 (qg-mobile.css): 767px 이하에서
// 본문 17px·누르는 곳 48px 이상, 표는 칸 이름이 붙은 카드로 흘리고, 글자 크기 5단계·선명하게 보기를 고를 수 있다.
const { test, expect } = require('@playwright/test');
const AxeBuilder = require('@axe-core/playwright').default;
const { open } = require('./helpers');

test.use({ viewport: { width: 390, height: 844 } });

// 화면에 보이는 누를 수 있는 것 중 48px 보다 작은 것 (확인란은 48px 칸 안에 있으면 통과)
async function smallTargets(page) {
  return page.evaluate(() => {
    const out = [];
    document.querySelectorAll('button, a[href], select, input:not([type=hidden]), [role=tab], tr.pick').forEach((el) => {
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height || getComputedStyle(el).visibility === 'hidden') { return; }
      if (el.closest('.visually-hidden, [hidden], dialog:not([open]), thead')) { return; }
      let box = r;
      if (el.matches('input[type=checkbox], input[type=radio]')) { box = (el.closest('td, label') || el).getBoundingClientRect(); }
      if (el.matches('a') && el.closest('p, li, dd, .v-crumb')) { return; } // 문장 안 링크는 WCAG 2.5.8 예외
      if (Math.min(box.width, box.height) < 44 || box.height < 48 && !el.matches('a')) { out.push(`${el.tagName.toLowerCase()}#${el.id || ''}.${el.className} ${Math.round(box.width)}x${Math.round(box.height)}`); }
    });
    return out;
  });
}

test('목록: 본문 17px, 행은 칸 이름이 붙은 카드, 누르는 곳 48px 이상', async ({ page }) => {
  await open(page, '/qms/index.html');
  const row = page.locator('#rows tr.pick').first();
  await row.waitFor();
  expect(await page.evaluate(() => parseFloat(getComputedStyle(document.body).fontSize))).toBe(17);
  expect(await row.evaluate((tr) => getComputedStyle(tr).display)).not.toBe('table-row');
  await expect(row.locator('td[data-label="상태"]')).toHaveCount(1);
  await expect(row.locator('td[data-label="설비"]')).toHaveCount(1);
  expect(await smallTargets(page)).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
});

test('조치: 표가 카드로 바뀌고, 승인 단추는 56px', async ({ page }) => {
  await open(page, '/qms/actions.html');
  await page.getByText('라인 정지·체크 링 교체').waitFor();
  await expect(page.locator('#rows td[data-label="위험도"]').first()).toBeVisible();
  const h = await page.getByRole('button', { name: /승인/ }).first().evaluate((b) => b.getBoundingClientRect().height);
  expect(h).toBeGreaterThanOrEqual(48);
  expect(await smallTargets(page)).toEqual([]);
});

test('글자 크기 5단계와 선명하게 보기를 고르면 다음 화면에도 남는다', async ({ page }) => {
  await open(page, '/qms/index.html');
  await page.getByRole('button', { name: /글자·화면/ }).click();
  const dlg = page.getByRole('dialog', { name: '글자 크기와 화면 설정' });
  await expect(dlg.getByRole('radio')).toHaveCount(5);
  await dlg.getByRole('radio', { name: '크게', exact: true }).check({ force: true });
  await dlg.getByRole('checkbox', { name: /선명하게/ }).check();
  expect(await page.evaluate(() => document.documentElement.style.zoom)).toBe('1.3');
  await page.keyboard.press('Escape');
  await expect(dlg).toBeHidden();
  await page.goto('/qms/actions.html');
  expect(await page.evaluate(() => [document.documentElement.style.zoom, document.documentElement.getAttribute('data-qg-contrast')])).toEqual(['1.3', 'high']);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
});

test('axe: 모바일 목록과 설정 창', async ({ page }) => {
  await open(page, '/qms/index.html');
  await page.locator('#rows tr.pick').first().waitFor();
  await page.getByRole('button', { name: /글자·화면/ }).click();
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  expect(r.violations.map((v) => `${v.id}: ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`)).toEqual([]);
});
