// 에이전트 조치 화면 (actions.html) 스모크
const { test, expect } = require('@playwright/test');
const { open, calls } = require('./helpers');

test('조치 목록과 필터별 건수가 보인다', async ({ page }) => {
  await open(page, '/qms/actions.html');
  await expect(page.getByText('라인 정지·체크 링 교체')).toBeVisible();
  await expect(page.locator('[data-count="WAITING_APPROVAL"]').first()).toHaveText('1');
  await expect(page.locator('[data-count="AUTO"]').first()).toHaveText('2');
  await expect(page.locator('[data-count="ESCALATED"]').first()).toHaveText('1');
});

test('승인 대기 행에 근거(확신도·검증·분석 번호)와 묶인 같은 판단 수가 보인다', async ({ page }) => {
  await open(page, '/qms/actions.html');
  const row = page.locator('#rows tr', { hasText: '라인 정지·체크 링 교체' });
  await expect(row).toContainText('확신도 97 · 검증 통과 · 분석 #2101');
  await expect(row).toContainText('같은 판단 3번 더 · 마지막 분석 #2109');
});

test('고위험 조치를 승인하면 승인 API 를 부른다', async ({ page }) => {
  const s = await open(page, '/qms/actions.html');
  await page.getByRole('button', { name: '승인·실행' }).click();
  const dlg = page.locator('dialog[open]');
  if (await dlg.count()) { await dlg.getByRole('button', { name: '승인·실행' }).click(); }
  await expect(page.getByText(/조치 12 승인/)).toBeVisible();
  expect(calls(s, 'POST', '/actions/12/approve')).toHaveLength(1);
});

test('판단 보류 목록: 순번·보류 사유·설비·시각 열과 건수', async ({ page }) => {
  await open(page, '/qms/actions.html');
  await page.getByRole('tab', { name: /판단 보류/ }).click();
  const heads = await page.locator('#p-holds thead th').allTextContents();
  expect(heads.map((h) => h.trim())).toEqual(['순번', '보류 사유', '설비', '시각']);
  await expect(page.locator('#hold-rows tr')).toHaveCount(2);
  await expect(page.locator('#hold-rows tr').first()).toContainText('재확인 상한 도달');
  await expect(page.locator('#hold-rows tr').first()).toContainText('IMM-02');
  await expect(page.locator('#hold-rows tr').first()).toContainText('2026-11-15 17:20');
  await expect(page.locator('#hold-count')).toHaveText('2');
});

test('판단 보류: 행을 누르면 상세로, 위치 링크로 목록에 돌아온다', async ({ page }) => {
  await open(page, '/qms/actions.html');
  await page.getByRole('tab', { name: /판단 보류/ }).click();
  await page.locator('#hold-rows tr').first().click();
  await expect(page).toHaveURL(/#hold\/1301$/);
  await expect(page.locator('#h-title')).toContainText('재확인 상한 도달 · IMM-02');
  await expect(page.locator('#h-summary')).toContainText('압력 변동이 간헐적으로');
  await expect(page.locator('#h-meta')).toContainText('#1301');
  await expect(page.locator('#p-holds')).toBeHidden();
  await page.locator('#back-to-holds').click();
  await expect(page.locator('#view-hold')).toBeHidden();
  await expect(page.getByRole('tab', { name: /판단 보류/ })).toHaveAttribute('aria-selected', 'true');
  await page.locator('#hold-rows tr').nth(1).focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#hold\/1288$/);
  await page.goBack();
  await expect(page.locator('#view-hold')).toBeHidden();
});

test('판단 보류 상세 주소로 바로 열고, 없는 번호는 안내한다', async ({ page }) => {
  await open(page, '/qms/actions.html#hold/1288');
  await expect(page.locator('#h-title')).toContainText('검증 지적 남음 · IMM-01');
  await page.goto('/qms/actions.html#hold/9999');
  await page.reload();
  await expect(page.locator('#h-missing')).toContainText('검토 대기 중인 판단 보류가 아닙니다');
});

test('판단 보류 상세: 요약을 만들고 메모와 함께 검토 완료하면 목록으로 돌아간다', async ({ page }) => {
  const s = await open(page, '/qms/actions.html#hold/1301');
  await page.getByRole('button', { name: /요약·현장 확인 항목 만들기/ }).click();
  await expect(page.getByText('압력 센서 배선 확인')).toBeVisible();
  await page.getByPlaceholder('확인·처리 메모').fill('센서 배선 정상');
  await page.getByRole('button', { name: '검토 완료' }).click();
  await expect(page.getByText(/판단 보류를 검토 완료로 닫았습니다/)).toBeVisible();
  expect(calls(s, 'POST', '/holds/1301/resolve')[0].body.note).toBe('센서 배선 정상');
  await expect(page.locator('#view-hold')).toBeHidden();
  await expect(page.locator('#hold-rows tr')).toHaveCount(1);
});

test('조회 전용 계정에는 승인 버튼이 없다', async ({ page }) => {
  await open(page, '/qms/actions.html', 'VIEWER');
  await expect(page.getByText('라인 정지·체크 링 교체')).toBeVisible();
  await expect(page.getByRole('button', { name: '승인·실행' })).toHaveCount(0);
});

test('판단 보류 메모를 쓰는 동안 10초 새로 읽기가 메모를 지우지 않는다', async ({ page }) => {
  await page.clock.install();
  await open(page, '/qms/actions.html#hold/1301');
  await page.getByPlaceholder('확인·처리 메모').fill('배선 확인 중');
  await page.clock.fastForward(21000);
  await expect(page.getByPlaceholder('확인·처리 메모')).toHaveValue('배선 확인 중');
});

test('상태 거르개로 승인 대기만 본다', async ({ page }) => {
  await open(page, '/qms/actions.html');
  await page.getByRole('button', { name: /승인 대기/ }).click();
  await expect(page.getByRole('button', { name: /승인 대기/ })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#rows tr')).toHaveCount(1);
});

test('좁은 화면(390px)에서 판단 보류 탭이 가로로 넘치지 않는다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await open(page, '/qms/actions.html');
  await page.getByRole('tab', { name: /판단 보류/ }).click();
  await page.locator('#hold-rows tr').first().waitFor();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.locator('#hold-rows tr').first().click();
  await page.getByPlaceholder('확인·처리 메모').waitFor();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
