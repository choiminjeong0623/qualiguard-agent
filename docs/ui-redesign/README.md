# QMS 화면 리디자인 전후 (2026-11-23)

같은 흉내 데이터(`e2e/mock-api.js`)로 찍은 사진입니다. 실제 데이터 화면은 맥에서 앱을 띄워 다시 찍어야 합니다.
다시 찍기: `cd e2e && QG_CAPTURE_LABEL=after npx playwright test --project=capture`

| 화면 | 전 | 후 |
|---|---|---|
| 부적합 목록 | (상세와 한 화면) | [after/desktop-ncr-list.png](after/desktop-ncr-list.png) |
| 부적합 상세 · 초안 | [before/desktop-ncr-draft.png](before/desktop-ncr-draft.png) | [after/desktop-ncr-draft.png](after/desktop-ncr-draft.png) |
| 부적합 상세 · 근거 탭 | (아코디언) | [after/desktop-ncr-evidence.png](after/desktop-ncr-evidence.png) |
| 부적합 상세 · 승인 | [before/desktop-ncr-approved.png](before/desktop-ncr-approved.png) | [after/desktop-ncr-approved.png](after/desktop-ncr-approved.png) |
| 조치·판단 보류 | [before/desktop-actions.png](before/desktop-actions.png) | [after/desktop-actions.png](after/desktop-actions.png) |
| 출하 시리얼 | [before/desktop-shipment.png](before/desktop-shipment.png) | [after/desktop-shipment.png](after/desktop-shipment.png) |
| 로그인 | [before/desktop-login.png](before/desktop-login.png) | [after/desktop-login.png](after/desktop-login.png) |

모바일(390px) 사진은 같은 폴더의 `mobile-*.png` 입니다. 무엇을 왜 바꿨는지는 [ADR-009 결정 6](../decisions/ADR-009-qms-review-screen.md)에 있습니다.

## v3 테마 (2026-11-24)

확정 목업을 화면 코드에 옮긴 뒤 같은 흉내 데이터로 찍은 사진입니다 ([ADR-009 결정 7](../decisions/ADR-009-qms-review-screen.md)).
다시 찍기: `cd e2e && QG_CAPTURE_LABEL=v3 npx playwright test --project=capture`

| 화면 | 데스크톱 | 모바일 |
|---|---|---|
| 부적합 목록 | [v3/desktop-ncr-list.png](v3/desktop-ncr-list.png) | [v3/mobile-ncr-list.png](v3/mobile-ncr-list.png) |
| 부적합 상세 · 초안 | [v3/desktop-ncr-draft.png](v3/desktop-ncr-draft.png) | [v3/mobile-ncr-draft.png](v3/mobile-ncr-draft.png) |
| 부적합 상세 · 근거 탭 | [v3/desktop-ncr-evidence.png](v3/desktop-ncr-evidence.png) | [v3/mobile-ncr-evidence.png](v3/mobile-ncr-evidence.png) |
| 부적합 상세 · 승인 | [v3/desktop-ncr-approved.png](v3/desktop-ncr-approved.png) | [v3/mobile-ncr-approved.png](v3/mobile-ncr-approved.png) |
| 조치·판단 보류 | [v3/desktop-actions.png](v3/desktop-actions.png) | [v3/mobile-actions.png](v3/mobile-actions.png) |
| 출하 시리얼 | [v3/desktop-shipment.png](v3/desktop-shipment.png) | [v3/mobile-shipment.png](v3/mobile-shipment.png) |
