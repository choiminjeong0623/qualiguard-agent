// 출하 시리얼 화면 (shipment.html) 스모크
const { test, expect } = require('@playwright/test');
const { open, calls } = require('./helpers');

test('임시 시리얼로 찾고 확정 시리얼을 연결한다', async ({ page }) => {
  const s = await open(page, '/qms/shipment.html');
  await expect(page.getByText('SN-2026-000120')).toBeVisible();
  await page.getByPlaceholder(/임시 시리얼/).fill('TMP-000123');
  await page.getByRole('button', { name: '찾기' }).click();
  await expect(page.getByText(/IMM-01 #321/).first()).toBeVisible();
  await page.getByPlaceholder(/SN-2026-000123/).fill('SN-2026-000123');
  await page.getByRole('button', { name: '연결', exact: true }).click();
  await expect(page.getByText(/SN-2026-000123 연결했습니다/)).toBeVisible();
  expect(calls(s, 'POST', '/shipment/confirm')[0].body.confirmedSerial).toBe('SN-2026-000123');
});

test('확정 시리얼로 추적하면 공정 값과 경보가 보인다', async ({ page }) => {
  await open(page, '/qms/shipment.html');
  const tab = page.getByRole('tab', { name: /추적/ });
  if (await tab.count()) { await tab.click(); }
  await page.getByPlaceholder('확정 시리얼').fill('SN-2026-000120');
  await page.getByRole('button', { name: '추적' }).click();
  await expect(page.getByText(/평균 이동/)).toBeVisible();
  await expect(page.getByText(/AI-2026-0110/)).toBeVisible();
});
