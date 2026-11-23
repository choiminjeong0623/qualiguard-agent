# QMS 화면 스모크 테스트

백엔드·DB·Gemini 키 없이 QMS 정적 화면(`qms-app/src/main/resources/static/qms/`)을 띄우고,
API 는 [`mock-api.js`](mock-api.js) 가 대신 답합니다. UI 리디자인 중 기존 기능이 깨지지 않았는지 확인하는 기준입니다.

```bash
cd e2e
npm ci
npx playwright install chromium       # 처음 한 번
npm test                              # 화면 흐름 22개 + 접근성 12개 (부적합 검토·조치·출하 시리얼·로그인)
QG_CAPTURE_LABEL=after npm run capture   # 화면 사진 → docs/ui-redesign/after/
```

- 확인하는 것: 목록·건수, 검색, 저장 뒤 승인 순서, 반려 사유 필수, 조치 지정, 조치 완료, MES/ERP 결과 표시,
  고위험 조치 승인, 판단 보류 요약·검토 완료, 시리얼 연결·추적, 조회 전용 계정 제한, CSRF 헤더·폼 값.
- 접근성(`tests/a11y.spec.js`): axe-core 로 WCAG 2.2 A·AA 규칙(대비, 이름 없는 버튼·입력, 탭·표 구조 등)을 화면 9개와 대화상자에서 보고,
  키보드만으로 목록 → 상세 → 승인 확인까지 가는지, 390px 에서 가로로 넘치지 않는지 봅니다. 자동 점검은 문제의 일부만 잡습니다(화면 낭독기 실사용 확인은 하지 않음).
- 확인하지 않는 것: 서버 쪽 상태 규칙·권한(서버 테스트 `NcrSecurityTest` 등이 맡음), 실제 응답 모양의 변화.
  가짜 응답은 컨트롤러의 컬럼 이름을 따랐으므로 API 를 바꾸면 `mock-api.js` 도 같이 고칩니다.
- `docs/ui-redesign/before/` 는 리디자인 전(main `9ccbf1d`), `after/` 는 리디자인 후 화면입니다.
