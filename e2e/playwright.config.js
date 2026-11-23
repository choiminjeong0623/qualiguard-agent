// QMS 화면 스모크 테스트 설정.
// 화면은 qms-app 의 정적 파일을 그대로 띄우고, API 는 mock-api.js 가 대신 답한다 (백엔드·DB·Gemini 키 불필요).
const { defineConfig } = require('@playwright/test');

const PORT = 4310;

module.exports = defineConfig({
  testDir: './tests',
  timeout: 30000,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    trace: 'off',
  },
  webServer: {
    command: `python3 -m http.server ${PORT} --bind 127.0.0.1 --directory ../qms-app/src/main/resources/static`,
    url: `http://127.0.0.1:${PORT}/qms/login.html`,
    reuseExistingServer: !process.env.CI,
  },
  projects: [
    // 스모크: 화면이 뜨고 핵심 동작이 올바른 API 를 부르는지
    { name: 'smoke', testIgnore: /capture\.spec/, use: { viewport: { width: 1440, height: 900 } } },
    // 화면 사진: 리디자인 전후 비교용 (docs/ui-redesign/). 판정은 하지 않는다
    { name: 'capture', testMatch: /capture\.spec/ },
  ],
});
