// 로그인 화면: 오류·로그아웃 안내, CSRF 토큰을 폼에 싣는지
const { test, expect } = require('@playwright/test');
const { cdn } = require('./helpers');

test.beforeEach(async ({ page }) => { await cdn(page); });

test('로그인 실패 안내', async ({ page }) => {
  await page.goto('/qms/login.html?error');
  await expect(page.getByText('아이디 또는 비밀번호가 맞지 않습니다.')).toBeVisible();
});

test('로그인 폼이 CSRF 토큰을 함께 보낸다', async ({ page }) => {
  await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'test-csrf', url: 'http://127.0.0.1:4310' }]);
  let posted = null;
  await page.route('**/login', (route) => { posted = route.request().postData(); return route.fulfill({ status: 200, body: 'ok' }); });
  await page.goto('/qms/login.html');
  await page.getByLabel('아이디').fill('reviewer');
  await page.getByLabel('비밀번호').fill('pw');
  await page.getByRole('button', { name: '로그인' }).click();
  await expect.poll(() => posted).toContain('_csrf=test-csrf');
});
