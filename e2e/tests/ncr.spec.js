// 부적합 검토 화면 (index.html) 스모크
const { test, expect } = require('@playwright/test');
const { open, calls } = require('./helpers');

test('검토 대기 목록과 상태별 건수가 보인다', async ({ page }) => {
  await open(page, '/qms/index.html');
  await expect(page.getByRole('row', { name: /AI-2026-0110/ })).toBeVisible();
  await expect(page.getByRole('row', { name: /AI-2026-0111/ })).toBeVisible();
  await expect(page.locator('[data-count="DRAFT"]').first()).toHaveText('2');
  await expect(page.locator('[data-count=""]').first()).toHaveText('5');
});

test('검색어로 목록을 거른다', async ({ page }) => {
  await open(page, '/qms/index.html');
  await page.getByRole('searchbox').fill('IMM-02');
  await expect(page.getByRole('row', { name: /AI-2026-0111/ })).toBeVisible();
  await expect(page.getByRole('row', { name: /AI-2026-0110/ })).toHaveCount(0);
});

test('초안을 열어 확정 원인을 적고 승인하면 저장 뒤 승인 API 를 부른다', async ({ page }) => {
  const s = await open(page, '/qms/index.html');
  await page.getByRole('row', { name: /AI-2026-0110/ }).click();
  await expect(page.getByRole('heading', { name: '실린더 온도 지속 하강' })).toBeVisible();
  await page.getByLabel(/확정 원인/).fill('2번 존 히터 밴드 단선');
  await page.getByRole('button', { name: '승인', exact: true }).click();
  await confirmIfAsked(page);
  await expect(page.getByText(/승인했습니다/)).toBeVisible();
  const put = calls(s, 'PUT', '/AI-2026-0110');
  expect(put).toHaveLength(1);
  expect(put[0].body.fields.rootCause).toBe('2번 존 히터 밴드 단선');
  expect(put[0].csrf).toBe('test-csrf');
  expect(calls(s, 'POST', '/AI-2026-0110/approve')).toHaveLength(1);
});

test('반려는 사유가 있어야 한다', async ({ page }) => {
  const s = await open(page, '/qms/index.html');
  await page.getByRole('row', { name: /AI-2026-0111/ }).click();
  await startReject(page);
  await submitReject(page);
  await expect(page.getByText('반려 사유를 입력하세요.')).toBeVisible();
  expect(calls(s, 'POST', '/reject')).toHaveLength(0);
  await page.locator('dialog[open]').getByPlaceholder('반려 사유').fill('단발성 노이즈');
  await submitReject(page);
  await expect(page.getByText('반려했습니다.')).toBeVisible();
  expect(calls(s, 'POST', '/AI-2026-0111/reject')[0].body.reason).toBe('단발성 노이즈');
});

test('승인된 부적합: MES/ERP 처리 결과와 조치 완료', async ({ page }) => {
  const s = await open(page, '/qms/index.html');
  await page.getByRole('tab', { name: /승인\s*1/ }).click();
  await page.getByRole('row', { name: /AI-2026-0036/ }).click();
  await showTab(page, /MES\/ERP/);
  await expect(page.getByText(/MES: 처리 완료 WO-55120/)).toBeVisible();
  await expect(page.getByText(/ERP: 처리 완료 QN-20261114-0007/)).toBeVisible();
  await page.getByPlaceholder(/실제로 한 조치/).fill('커넥터 교체');
  await page.getByRole('button', { name: '조치 완료', exact: true }).click();
  await confirmIfAsked(page);
  await expect(page.getByText('조치 완료로 닫았습니다.')).toBeVisible();
  expect(calls(s, 'POST', '/AI-2026-0036/close')[0].body.actionTaken).toBe('커넥터 교체');
});

test('조치 지정(담당자·기한)을 저장한다', async ({ page }) => {
  const s = await open(page, '/qms/index.html');
  await page.getByRole('row', { name: /AI-2026-0110/ }).click();
  await page.getByLabel('담당자').fill('성형1팀 김반장');
  await page.getByLabel('조치 기한').fill('2026-11-30');
  await page.getByRole('button', { name: '조치 지정 저장' }).click();
  await expect(page.getByText('조치 지정을 저장했습니다.')).toBeVisible();
  const put = calls(s, 'PUT', '/AI-2026-0110/assignment')[0].body;
  expect(put.actionOwner).toBe('성형1팀 김반장');
  expect(put.actionDueDate).toBe('2026-11-30');
});

test('에이전트 분석 기록과 연결된 경보가 보인다', async ({ page }) => {
  await open(page, '/qms/index.html');
  await page.getByRole('row', { name: /AI-2026-0110/ }).click();
  await showTab(page, /경보|근거/);
  await expect(page.getByText(/관리 범위를 벗어남/)).toBeVisible();
  await showTab(page, /분석|근거/);
  await expect(page.getByText(/부적합 초안을 발행합니다/)).toBeVisible();
  await expect(page.getByText(/검증 통과/).first()).toBeVisible();
});

test('조회 전용 계정은 입력과 승인 버튼이 막혀 있다', async ({ page }) => {
  await open(page, '/qms/index.html', 'VIEWER');
  await page.getByRole('row', { name: /AI-2026-0110/ }).click();
  await expect(page.getByText(/조회 전용 계정입니다/)).toBeVisible();
  await expect(page.getByLabel(/확정 원인/)).toBeDisabled();
  const approve = page.getByRole('button', { name: '승인', exact: true });
  if (await approve.count()) { await expect(approve).toBeHidden({ timeout: 1000 }).catch(() => expect(approve).toBeDisabled()); }
});

// ── 리디자인 전후에 모두 통하도록 둔 도우미 ──────────────────────
// 확인 대화상자가 있으면 확인을 누른다 (리디자인 2단계에서 생김)
async function confirmIfAsked(page) {
  const dlg = page.locator('dialog[open]');
  if (await dlg.count()) { await dlg.getByRole('button').filter({ hasNotText: '취소' }).first().click(); }
}
// 탭이 있으면 그 탭을 연다 (리디자인 3단계), 없으면 아코디언을 연다 (현행)
async function showTab(page, name) {
  const tab = page.getByRole('tab', { name });
  if (await tab.count()) { await tab.first().click(); return; }
  const acc = page.locator('.accordion-button.collapsed').filter({ hasText: name });
  if (await acc.count()) { await acc.first().click(); }
}
// 반려: 리디자인 2단계부터 [반려…] 버튼 → 대화상자에서 사유 입력
async function startReject(page) {
  await page.getByRole('button', { name: '반려…' }).click();
  await expect(page.locator('dialog[open]')).toBeVisible();
}
async function submitReject(page) {
  await page.locator('dialog[open]').getByRole('button', { name: '반려', exact: true }).click();
}

test('승인 확인 대화상자에서 취소하면 승인하지 않는다', async ({ page }) => {
  const s = await open(page, '/qms/index.html');
  await page.getByRole('row', { name: /AI-2026-0110/ }).click();
  await page.getByLabel(/확정 원인/).fill('히터 단선');
  await page.getByRole('button', { name: '승인', exact: true }).click();
  const dlg = page.locator('dialog[open]');
  await expect(dlg).toContainText('MES');
  await dlg.getByRole('button', { name: '취소' }).click();
  await expect(dlg).toHaveCount(0);
  expect(calls(s, 'POST', '/approve')).toHaveLength(0);
});

test('사이드 메뉴로 화면을 옮기고 현재 화면을 표시한다', async ({ page }) => {
  await open(page, '/qms/index.html');
  const nav = page.getByRole('navigation', { name: '주 메뉴' });
  await expect(nav.getByRole('link', { name: '부적합 검토' })).toHaveAttribute('aria-current', 'page');
  await nav.getByRole('link', { name: /조치/ }).click();
  await expect(page).toHaveURL(/actions\.html/);
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
});

test('상세는 주소에 남고, 목록으로 돌아가거나 다음 건으로 옮길 수 있다', async ({ page }) => {
  await open(page, '/qms/index.html');
  await page.getByRole('row', { name: /AI-2026-0110/ }).click();
  await expect(page).toHaveURL(/#AI-2026-0110$/);
  await expect(page.getByRole('tab', { name: '부적합 내용' })).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('tab', { name: '부적합 내용' }).press('ArrowRight');
  await expect(page.getByRole('tab', { name: /근거/ })).toHaveAttribute('aria-selected', 'true');
  const next = page.getByRole('button', { name: '다음' });
  const prev = page.getByRole('button', { name: '이전' });
  // 목록은 작성 시각 최신순: 0111 → 0110
  await expect(next).toBeDisabled();
  await prev.click();
  await expect(page.getByRole('heading', { name: '사출 압력 단발 변동' })).toBeVisible();
  await page.getByRole('link', { name: '부적합 검토' }).first().click();
  await expect(page.getByRole('row', { name: /AI-2026-0111/ })).toBeVisible();
  await page.goto(page.url().replace(/#.*$/, '') + '#AI-2026-0110');
  await page.reload();
  await expect(page.getByRole('heading', { name: '실린더 온도 지속 하강' })).toBeVisible();
});
