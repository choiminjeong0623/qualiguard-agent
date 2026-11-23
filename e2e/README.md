# QMS 화면 스모크 테스트

백엔드·DB·Gemini 키 없이 QMS 정적 화면(`qms-app/src/main/resources/static/qms/`)을 띄우고,
API 는 [`mock-api.js`](mock-api.js) 가 대신 답합니다. UI 리디자인 중 기존 기능이 깨지지 않았는지 확인하는 기준입니다.

```bash
cd e2e
npm ci
npx playwright install chromium       # 처음 한 번
npm test                              # 스모크 16개 (부적합 검토·에이전트 조치·출하 시리얼·로그인)
QG_CAPTURE_LABEL=after npm run capture   # 화면 사진 → docs/ui-redesign/after/
```

- 확인하는 것: 목록·건수, 검색, 저장 뒤 승인 순서, 반려 사유 필수, 조치 지정, 조치 완료, MES/ERP 결과 표시,
  고위험 조치 승인, 판단 보류 요약·검토 완료, 시리얼 연결·추적, 조회 전용 계정 제한, CSRF 헤더·폼 값.
- 확인하지 않는 것: 서버 쪽 상태 규칙·권한(서버 테스트 `NcrSecurityTest` 등이 맡음), 실제 응답 모양의 변화.
  가짜 응답은 컨트롤러의 컬럼 이름을 따랐으므로 API 를 바꾸면 `mock-api.js` 도 같이 고칩니다.
- `docs/ui-redesign/before/` 는 리디자인 전(main `9ccbf1d`) 화면입니다.
