const path = require('path');
const { install } = require('../mock-api');

// 현행 화면은 jQuery·Tabler 를 CDN 에서 싣는다. CDN 이 막힌 환경(폐쇄망, 일부 CI)에서도 돌도록
// 같은 버전의 npm 패키지 파일로 대신 답한다.
const NM = path.join(__dirname, '..', 'node_modules');
async function cdn(page) {
  await page.route('https://cdnjs.cloudflare.com/ajax/libs/jquery/3.7.1/jquery.min.js',
    (r) => r.fulfill({ path: path.join(NM, 'jquery/dist/jquery.min.js'), contentType: 'application/javascript' }));
  await page.route('https://cdn.jsdelivr.net/npm/@tabler/core@1.4.0/dist/css/tabler.min.css',
    (r) => r.fulfill({ path: path.join(NM, '@tabler/core/dist/css/tabler.min.css'), contentType: 'text/css' }));
}

// 가짜 API 를 깔고 CSRF 쿠키를 넣은 뒤 화면을 연다. 반환값 s.calls 에 쓰기 요청이 쌓인다.
async function open(page, path, role) {
  await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'test-csrf', url: 'http://127.0.0.1:4310' }]);
  await cdn(page);
  const s = await install(page, { role });
  await page.goto(path);
  return s;
}

function calls(s, method, suffix) {
  return s.calls.filter((c) => c.method === method && c.path.endsWith(suffix));
}

module.exports = { open, calls, cdn };
