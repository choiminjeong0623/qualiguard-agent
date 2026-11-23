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

test('고위험 조치를 승인하면 승인 API 를 부른다', async ({ page }) => {
  const s = await open(page, '/qms/actions.html');
  await page.getByRole('button', { name: '승인·실행' }).click();
  const dlg = page.locator('dialog[open]');
  if (await dlg.count()) { await dlg.getByRole('button', { name: '승인·실행' }).click(); }
  await expect(page.getByText(/조치 12 승인/)).toBeVisible();
  expect(calls(s, 'POST', '/actions/12/approve')).toHaveLength(1);
});

test('판단 보류: 요약을 만들고 메모와 함께 검토 완료', async ({ page }) => {
  const s = await open(page, '/qms/actions.html');
  const tab = page.getByRole('tab', { name: /판단 보류/ });
  if (await tab.count()) { await tab.click(); }
  await page.getByRole('button', { name: /요약·현장 확인 항목 만들기/ }).click();
  await expect(page.getByText('압력 센서 배선 확인')).toBeVisible();
  await page.getByPlaceholder('확인·처리 메모').fill('센서 배선 정상');
  await page.getByRole('button', { name: '검토 완료' }).click();
  await expect(page.getByText(/판단 보류를 검토 완료로 닫았습니다/)).toBeVisible();
  expect(calls(s, 'POST', '/holds/1301/resolve')[0].body.note).toBe('센서 배선 정상');
});

test('조회 전용 계정에는 승인 버튼이 없다', async ({ page }) => {
  await open(page, '/qms/actions.html', 'VIEWER');
  await expect(page.getByText('라인 정지·체크 링 교체')).toBeVisible();
  await expect(page.getByRole('button', { name: '승인·실행' })).toHaveCount(0);
});

test('판단 보류 메모를 쓰는 동안 10초 새로 읽기가 메모를 지우지 않는다', async ({ page }) => {
  await page.clock.install();
  await open(page, '/qms/actions.html');
  await page.getByRole('tab', { name: /판단 보류/ }).click();
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
