// 접근성 자동 점검 (UI 리디자인 5단계): axe-core 로 WCAG 2.2 A·AA 규칙을 본다.
// 자동 점검은 문제의 일부만 잡는다. 키보드 흐름은 아래 시험과 각 화면 시험에서 따로 본다.
const { test, expect } = require('@playwright/test');
const AxeBuilder = require('@axe-core/playwright').default;
const { open, cdn } = require('./helpers');

async function check(page, label) {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  const lines = r.violations.map((v) => `${v.id} (${v.impact}): ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`);
  expect(lines, label).toEqual([]);
}

const PAGES = [
  ['부적합 목록', async (p) => { await open(p, '/qms/index.html'); await p.getByRole('row', { name: /AI-2026-0110/ }).waitFor(); }],
  ['부적합 상세', async (p) => { await open(p, '/qms/index.html'); await p.getByRole('row', { name: /AI-2026-0110/ }).click(); await p.getByRole('heading', { name: '실린더 온도 지속 하강' }).waitFor(); }],
  ['부적합 분류·조치 지정 탭', async (p) => { await open(p, '/qms/index.html#AI-2026-0110'); await p.getByRole('tab', { name: /분류/ }).click(); await p.getByLabel('담당자').waitFor(); }],
  ['부적합 근거 탭', async (p) => { await open(p, '/qms/index.html#AI-2026-0110'); await p.getByRole('tab', { name: /근거/ }).click(); await p.getByText(/부적합 초안을 발행합니다/).waitFor(); }],
  ['부적합 승인 건', async (p) => { await open(p, '/qms/index.html#AI-2026-0036'); await p.getByRole('tab', { name: /이력/ }).click(); await p.getByText(/MES: 처리 완료/).waitFor(); }],
  ['조치', async (p) => { await open(p, '/qms/actions.html'); await p.getByText('라인 정지·체크 링 교체').waitFor(); }],
  ['판단 보류', async (p) => { await open(p, '/qms/actions.html'); await p.getByRole('tab', { name: /판단 보류/ }).click(); await p.locator('#hold-rows tr').first().waitFor(); }],
  ['판단 보류 상세', async (p) => { await open(p, '/qms/actions.html#hold/1301'); await p.getByPlaceholder('확인·처리 메모').waitFor(); }],
  ['출하 연결', async (p) => { await open(p, '/qms/shipment.html'); await p.getByText('SN-2026-000120').waitFor(); }],
  ['출하 추적', async (p) => { await open(p, '/qms/shipment.html'); await p.getByRole('link', { name: 'SN-2026-000120' }).click(); await p.getByText(/평균 이동/).waitFor(); }],
  ['로그인', async (p) => { await cdn(p); await p.goto('/qms/login.html?error'); }]
];

for (const [label, go] of PAGES) {
  test(`axe: ${label}`, async ({ page }) => { await go(page); await check(page, label); });
}

test('axe: 확인 대화상자', async ({ page }) => {
  await open(page, '/qms/index.html#AI-2026-0110');
  await page.getByRole('button', { name: '반려…' }).click();
  await page.locator('dialog[open]').waitFor();
  await check(page, '반려 대화상자');
});

test('키보드만으로 목록에서 상세를 열고 승인 확인까지 간다', async ({ page }) => {
  await open(page, '/qms/index.html');
  const row = page.getByRole('row', { name: /AI-2026-0110/ });
  await row.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: '실린더 온도 지속 하강' })).toBeFocused();
  await page.getByLabel(/확정 원인/).focus();
  await page.keyboard.type('히터 단선');
  await page.getByRole('button', { name: '승인', exact: true }).focus();
  await page.keyboard.press('Enter');
  const dlg = page.locator('dialog[open]');
  await expect(dlg.getByRole('button', { name: '승인' })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(dlg).toHaveCount(0);
});

test('좁은 화면(390px)에서 가로로 넘치지 않는다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const path of ['/qms/index.html', '/qms/index.html#AI-2026-0110', '/qms/actions.html', '/qms/shipment.html']) {
    await open(page, path);
    await page.waitForTimeout(200);
    const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(over, path).toBeLessThanOrEqual(0);
  }
});
