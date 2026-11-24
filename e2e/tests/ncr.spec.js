// 부적합 검토 화면 (index.html) 스모크
const { test, expect } = require('@playwright/test');
const { open, calls } = require('./helpers');

test('검토 대기 목록과 상태별 건수가 보인다', async ({ page }) => {
  await open(page, '/qms/index.html');
  await expect(page.getByRole('row', { name: /AI-2026-0110/ })).toBeVisible();
  await expect(page.getByRole('row', { name: /AI-2026-0111/ })).toBeVisible();
  await expect(page.locator('[data-count="DRAFT"]').first()).toHaveText('2');
  await expect(page.locator('#f-status option[value=""]')).toHaveText('전체 (5)');
  await expect(page.locator('#f-status option[value="DRAFT"]')).toHaveText('검토 대기 (2)');
});

test('검색어로 목록을 거른다', async ({ page }) => {
  await open(page, '/qms/index.html');
  await page.getByRole('searchbox').fill('IMM-02');
  await page.getByRole('button', { name: '검색' }).click();
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
  await page.getByLabel('상태').selectOption('APPROVED');
  await page.getByRole('row', { name: /AI-2026-0036/ }).click();
  await showTab(page, /이력/);
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
  await showTab(page, /분류/);
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
  await expect(page.locator('#analyses').getByText(/검증 통과/).first()).toBeVisible();
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

test('주 메뉴로 화면을 옮기고 현재 화면을 표시한다', async ({ page }) => {
  await open(page, '/qms/index.html');
  const nav = page.getByRole('navigation', { name: '주 메뉴' });
  await expect(nav.getByRole('link', { name: '부적합 검토' })).toHaveAttribute('aria-current', 'page');
  await nav.getByRole('link', { name: /조치/ }).click();
  await expect(page).toHaveURL(/actions\.html/);
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
});

test('상세는 주소에 남고, 탭은 화살표로 옮기며, 목록으로 돌아갈 수 있다', async ({ page }) => {
  await open(page, '/qms/index.html');
  await page.getByRole('row', { name: /AI-2026-0110/ }).click();
  await expect(page).toHaveURL(/#AI-2026-0110$/);
  await expect(page.getByRole('tab', { name: /검토·확정/ })).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('tab', { name: /검토·확정/ }).press('ArrowRight');
  await expect(page.getByRole('tab', { name: /분류/ })).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('link', { name: '부적합 검토', exact: true }).last().click();
  await expect(page.getByRole('row', { name: /AI-2026-0111/ })).toBeVisible();
  await page.goto(page.url().replace(/#.*$/, '') + '#AI-2026-0110');
  await page.reload();
  await expect(page.getByRole('heading', { name: /실린더 온도 지속 하강/ })).toBeVisible();
});

test('추정 원인을 확정 원인으로 옮기고, 줄의 [수정]으로 고친 값을 저장한다', async ({ page }) => {
  const s = await open(page, '/qms/index.html#AI-2026-0110');
  await page.getByRole('button', { name: '확정 원인으로 옮기기' }).click();
  await expect(page.getByLabel(/확정 원인/)).toHaveValue('2번 존 히터 밴드 열화로 온도 제어가 늦어짐.');
  await page.locator('[data-row="recommendedAction"]').getByRole('button', { name: '수정' }).click();
  await page.getByLabel('권고 조치').fill('히터 밴드 교체 후 20 shot 관찰');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByText('저장했습니다.')).toBeVisible();
  const f = calls(s, 'PUT', '/AI-2026-0110')[0].body.fields;
  expect(f.recommendedAction).toBe('히터 밴드 교체 후 20 shot 관찰');
  expect(f.rootCause).toBe('2번 존 히터 밴드 열화로 온도 제어가 늦어짐.');
  expect(f.causeCode).toBe('HEATER_DEGRADATION');
  expect(f.title).toBe('실린더 온도 지속 하강');
});

test('목록: 페이지당 건수를 고르고, 고른 행만 CSV·엑셀로 내려받는다', async ({ page }) => {
  await open(page, '/qms/index.html');
  await page.getByLabel('상태').selectOption('');
  await expect(page.locator('#list-count')).toHaveText('5건 중 1–5');
  await page.getByLabel('페이지당 건수').selectOption('30');
  await page.getByRole('checkbox', { name: 'AI-2026-0110 선택' }).check();
  await expect(page.getByText('선택 1건')).toBeVisible();
  const [csv] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'CSV' }).click()]);
  expect(csv.suggestedFilename()).toMatch(/^ncr-list-\d{8}-\d{4}\.csv$/);
  const text = require('fs').readFileSync(await csv.path(), 'utf8');
  expect(text.charCodeAt(0)).toBe(0xFEFF);
  const lines = text.trim().split(/\r\n/);
  expect(lines).toHaveLength(2);
  expect(lines[1]).toContain('AI-2026-0110,실린더 온도 지속 하강,검토 대기,IMM-01');
  const [xlsx] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '엑셀' }).click()]);
  expect(xlsx.suggestedFilename()).toMatch(/\.xlsx$/);
  const buf = require('fs').readFileSync(await xlsx.path());
  expect(buf.subarray(0, 2).toString()).toBe('PK');
  expect(buf.toString('utf8')).toContain('실린더 온도 지속 하강');
});

test('상세: 이 부적합 한 건을 엑셀로 내려받는다', async ({ page }) => {
  await open(page, '/qms/index.html#AI-2026-0110');
  await page.getByRole('heading', { name: /실린더 온도 지속 하강/ }).waitFor();
  const [dl] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '엑셀' }).click()]);
  expect(dl.suggestedFilename()).toMatch(/^AI-2026-0110-\d{8}-\d{4}\.xlsx$/);
});

test('조치 지정을 저장해도 아직 저장하지 않은 확정 원인은 남는다', async ({ page }) => {
  const s = await open(page, '/qms/index.html');
  await page.getByRole('row', { name: /AI-2026-0110/ }).click();
  await page.locator('#root-cause').fill('2번 존 히터 밴드 저항 35% 높음');
  await page.getByRole('tab', { name: /분류·조치 지정/ }).click();
  await page.locator('#a-owner').fill('성형1팀 박반장');
  await page.getByRole('button', { name: '조치 지정 저장' }).click();
  await expect(page.getByText(/저장하지 않은 다른 수정 1건은 그대로 남아 있습니다/)).toBeVisible();
  await expect(page.locator('#root-cause')).toHaveValue('2번 존 히터 밴드 저항 35% 높음');
  await page.locator('#btn-approve').click();
  await page.locator('dialog[open]').getByRole('button', { name: '승인' }).click();
  await expect(page.getByText(/승인했습니다/)).toBeVisible();
  expect(calls(s, 'PUT', '/AI-2026-0110')[0].body.fields.rootCause).toBe('2번 존 히터 밴드 저항 35% 높음');
});

test('조치 기한 칸에는 달력 버튼이 하나만 보인다', async ({ page }) => {
  await open(page, '/qms/index.html');
  await page.getByRole('row', { name: /AI-2026-0110/ }).click();
  await page.getByRole('tab', { name: /분류·조치 지정/ }).click();
  await expect(page.locator('#due-pick')).toBeVisible();
  // 브라우저 기본 달력 아이콘을 숨기는 규칙이 실제로 실렸는지 확인 (가상 요소는 계산 스타일로 읽을 수 없다)
  const hidden = await page.evaluate(() => [...document.styleSheets].some((sh) => {
    try { return [...sh.cssRules].some((r) => /calendar-picker-indicator/.test(r.selectorText || '') && r.style.display === 'none'); } catch (e) { return false; }
  }));
  expect(hidden).toBe(true);
});
