// 가짜 API: 백엔드·DB 없이 QMS 정적 화면을 돌리기 위해 /api/** 와 /logout 을 가로챈다.
// 응답 모양은 qms-app 컨트롤러가 돌려주는 JSON(프로시저 컬럼 이름 그대로)을 따른다.
// 테스트마다 새 상태를 만들고, 쓰기 요청은 상태를 바꾸고 요청 기록(calls)에 남긴다.

const ME = {
  REVIEWER: { username: 'reviewer', displayName: '김품질', role: 'REVIEWER' },
  VIEWER: { username: 'viewer', displayName: '이조회', role: 'VIEWER' },
};

const PIPELINE = JSON.stringify({
  triage: { severity: 'MEDIUM', type: 'PROCESS_SHIFT' },
  report: { mode: 'ANALYSIS_TEXT', cloudCalls: 0 },
  timeline: [
    { state: 'TRIAGED', atMs: 0 },
    { state: 'ANALYZING', atMs: 100, detail: 'MEDIUM · PROCESS_SHIFT' },
    { state: 'VERIFYING', atMs: 55400, detail: '시도 1: NCR_DRAFT / HEATER_DEGRADATION' },
    { state: 'WRITING', atMs: 55500, detail: '검증 PASS' },
    { state: 'AWAITING_APPROVAL', atMs: 55500, detail: '작성 ANALYSIS_TEXT' },
  ],
});

function ncr(no, o) {
  return Object.assign({
    ncr_no: no, equipment_id: 'IMM-01', status: 'DRAFT', created_by: 'AGENT', confidence: 85,
    title: '실린더 온도 지속 하강', cause_code: 'HEATER_DEGRADATION', defect_type: 'SHORT_SHOT',
    related_sensors: 'barrelTemp,injectionPressure',
    phenomenon: 'shot 325 이후 실린더 온도가 관리 하한 아래로 5 shot 연속 내려감.',
    suspected_cause: '2번 존 히터 밴드 열화로 온도 제어가 늦어짐.', root_cause: '',
    evidence: 'barrelTemp 5 shot 평균 228.51℃ (관리 범위 228.64~230.67℃)\nXBAR 규칙 위반 1건',
    recommended_action: '2번 존 히터 밴드 저항 측정 후 교체',
    occurred_at: '2026-11-15T07:40:00.000+00:00', created_at: '2026-11-15T07:41:10.000+00:00',
    reviewed_by: null, reviewed_at: null, review_comment: null,
    action_owner: null, action_due_date: null, affected_lots: 'L261115-IMM-01-03,L261115-IMM-01-04',
  }, o);
}

function initialState(role) {
  const ncrs = [
    ncr('AI-2026-0110', {}),
    ncr('AI-2026-0111', { equipment_id: 'IMM-02', title: '사출 압력 단발 변동', cause_code: 'SENSOR_FAULT', confidence: 62,
      created_at: '2026-11-15T08:02:00.000+00:00' }),
    ncr('AI-2026-0036', { equipment_id: 'IMM-03', status: 'APPROVED', title: '사출 압력 단발 변동', cause_code: 'SENSOR_FAULT',
      confidence: 78, root_cause: '압력 센서 커넥터 접촉 불량', reviewed_by: '품질 담당자',
      reviewed_at: '2026-11-14T13:30:00.000+00:00', action_owner: '성형1팀 김반장', action_due_date: '2026-11-20' }),
    ncr('AI-2026-0035', { status: 'CLOSED', title: '냉각수 유량 저하', cause_code: 'COOLING_ISSUE', root_cause: '냉각수 필터 막힘',
      reviewed_by: '품질 담당자', reviewed_at: '2026-11-14T10:00:00.000+00:00' }),
    ncr('AI-2026-0001', { status: 'REJECTED', title: '노이즈 경보', cause_code: 'NOISE', confidence: 40,
      reviewed_by: '품질 담당자', reviewed_at: '2026-11-13T09:00:00.000+00:00', review_comment: '단발성 노이즈' }),
  ];
  const actions = [
    { action_id: 11, equipment_id: 'IMM-01', cause_code: 'HEATER_DEGRADATION', action_code: 'HEATER_TEMP_COMPENSATE',
      risk: 'LOW', mode: 'AUTO', status: 'EXECUTED', attempt: 1, reasons: '저위험: 설정값 보정', effect_detail: '40 shot 뒤 확인 예정' },
    { action_id: 12, equipment_id: 'IMM-03', cause_code: 'CHECK_RING_WEAR', action_code: 'CHECK_RING_REPLACE',
      risk: 'HIGH', mode: 'MANUAL', status: 'WAITING_APPROVAL', attempt: 1, reasons: '고위험: 라인 정지 필요', effect_detail: null },
    { action_id: 13, equipment_id: 'IMM-02', cause_code: 'COOLING_ISSUE', action_code: 'COOLING_FLOW_INCREASE',
      risk: 'MEDIUM', mode: 'AUTO', status: 'RESOLVED', attempt: 1, reasons: null, effect_detail: '불량률 4.0% → 0.5%' },
    { action_id: 14, equipment_id: 'IMM-01', cause_code: 'SENSOR_FAULT', action_code: 'SENSOR_RECALIBRATE',
      risk: 'MEDIUM', mode: 'MANUAL', status: 'NOT_RESOLVED', attempt: 2, decided_by: '김품질', reasons: null, effect_detail: '효과 없음 · 재분석 요청' },
  ];
  const holds = [
    { analysis_id: 1301, trigger_type: 'RECHECK', as_of_shot: 410, equipment_id: 'IMM-02', decision: 'OBSERVE',
      cause_code: 'UNKNOWN', confidence: 45, hold_reason: 'RECHECK_LIMIT', hold_detail: '재확인 2회 상한 도달',
      summary: '압력 변동이 간헐적으로 반복되나 원인을 특정하지 못함', watch_items: '["injectionPressure","cushion"]', hold_brief: null,
      created_at: '2026-11-15T08:20:00.000+00:00' },
    { analysis_id: 1288, trigger_type: 'ALARM', as_of_shot: 1210, equipment_id: 'IMM-01', decision: 'NCR_DRAFT',
      cause_code: 'COOLING_ISSUE', confidence: 58, hold_reason: 'VERIFY_WEAK', hold_detail: '검증 지적 1건 남은 채 경보 종료',
      summary: '금형 온도가 오르지만 냉각수 유량 근거가 약함', watch_items: '["moldTemp"]', hold_brief: null,
      created_at: '2026-11-15T06:05:00.000+00:00' },
  ];
  const links = [
    { confirmed_serial: 'SN-2026-000120', provisional_serial: 'TMP-000120', equipment_id: 'IMM-01', shot_no: 300,
      inspection_result: 'OK', shipment_no: 'SH-1101', confirmed_at: '2026-11-15T09:00:00', confirmed_by: '김품질' },
  ];
  return { me: ME[role || 'REVIEWER'], ncrs, actions, holds, links, calls: [] };
}

function detail(s, no) {
  const n = s.ncrs.find((x) => x.ncr_no === no);
  if (!n) { return null; }
  const approved = n.status === 'APPROVED' || n.status === 'CLOSED';
  return Object.assign({}, n, {
    completeness: { passed: n.action_owner ? 11 : 9, total: 11, missing: n.action_owner ? [] : ['담당자', '조치 기한'] },
    anomalies: [{ shot_no: 325, rule_code: 'XBAR', sensor_name: 'barrelTemp',
      message: 'IMM-01 실린더 온도의 5 shot 평균 228.51℃ 이(가) 연속으로 관리 범위를 벗어남' }],
    analyses: n.created_by !== 'AGENT' ? [] : [{
      analysis_id: 1239, trigger_type: 'RECHECK', decision: 'NCR_DRAFT', cause_code: n.cause_code, as_of_shot: 365,
      confidence: n.confidence, latency_ms: 55500, verify_status: 'PASS', attempts: 1, verify_json: '{"fails":[],"warns":[]}',
      pipeline_json: PIPELINE,
      result_json: JSON.stringify({ reasoningSummary: '감시 항목이 shot 365 에서 모두 확인되어 부적합 초안을 발행합니다.', similarCases: ['AI-2026-0035'] }),
      tool_trace: 'getSpcSummary(IMM-01, 50) @7590ms\ngetSensorTrend(IMM-01, barrelTemp, 300, 20) @7626ms\nverify#1(PASS) @55468ms',
    }],
    integration: approved ? [{ event_id: 'ev-' + no, event_type: 'NCR_APPROVED', published_at: '2026-11-14T13:30:02.000+00:00', attempts: 1 }] : [],
    integrationStatus: approved ? [
      { event_id: 'ev-' + no, system_name: 'MES', status: 'PROCESSED', reference_no: 'WO-55120', message: '설비 점검 작업 지시', updated_at: '2026-11-14T13:30:03.000+00:00' },
      { event_id: 'ev-' + no, system_name: 'ERP', status: 'PROCESSED', reference_no: 'QN-20261114-0007', message: null, updated_at: '2026-11-14T13:30:04.000+00:00' },
    ] : [],
    history: n.reviewed_by ? [{ created_at: n.reviewed_at, actor: n.reviewed_by, action: n.status === 'REJECTED' ? 'REJECT' : 'APPROVE',
      comment: n.review_comment, changes_json: '{"rootCause":{"before":"","after":"x"}}' }] : [],
  });
}

const COLUMN = { suspectedCause: 'suspected_cause', rootCause: 'root_cause', recommendedAction: 'recommended_action',
  causeCode: 'cause_code', defectType: 'defect_type', relatedSensors: 'related_sensors' };

async function install(page, opts) {
  const s = initialState(opts && opts.role);
  const json = (route, body, status) => route.fulfill({ status: status || 200, contentType: 'application/json', body: JSON.stringify(body) });

  await page.route('**/logout', (route) => route.fulfill({ status: 200, body: '' }));
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const path = url.pathname;
    const method = req.method();
    let body = null;
    try { body = req.postDataJSON(); } catch (e) { body = null; }
    if (method !== 'GET') { s.calls.push({ method, path, body, csrf: req.headers()['x-xsrf-token'] }); }
    if (method !== 'GET' && s.me.role !== 'REVIEWER') { return json(route, { error: '권한 없음' }, 403); }

    if (path === '/api/qms/me') { return json(route, s.me); }
    if (path === '/api/qms/ncrs/knowledge') { return json(route, [{ total: 6, ai_approved: 2 }]); }
    if (path === '/api/qms/ncrs/review-stats') { return json(route, { decided: 3, asIsRate: 0.33, rejectionRate: 0.33 }); }
    if (path === '/api/qms/ncrs/completeness') { return json(route, { count: 4, completeRate: 0.5 }); }
    if (path === '/api/qms/ncrs' && method === 'GET') {
      const st = url.searchParams.get('status') || '';
      return json(route, s.ncrs.filter((n) => !st || n.status === st));
    }
    let m = path.match(/^\/api\/qms\/ncrs\/([^/]+)(\/.*)?$/);
    if (m) {
      const no = decodeURIComponent(m[1]);
      const sub = m[2] || '';
      const n = s.ncrs.find((x) => x.ncr_no === no);
      if (!n) { return json(route, { error: '없음' }, 404); }
      if (method === 'PUT' && sub === '') {
        Object.entries(body.fields || {}).forEach(([k, v]) => { n[COLUMN[k] || k] = v; });
      } else if (method === 'PUT' && sub === '/assignment') {
        n.action_owner = body.actionOwner || null; n.action_due_date = body.actionDueDate || null; n.affected_lots = body.affectedLots || null;
      } else if (sub === '/approve') {
        if (n.status !== 'DRAFT') { return json(route, { error: '초안이 아님' }, 409); }
        if (!n.root_cause) { return json(route, { error: '확정 원인이 필요합니다' }, 400); }
        n.status = 'APPROVED'; n.reviewed_by = s.me.displayName; n.reviewed_at = '2026-11-23T12:00:00.000+00:00';
      } else if (sub === '/reject') {
        n.status = 'REJECTED'; n.reviewed_by = s.me.displayName; n.review_comment = body.reason;
      } else if (sub === '/close') {
        n.status = 'CLOSED';
      }
      return json(route, detail(s, no));
    }
    if (path === '/api/qms/actions') { return json(route, s.actions); }
    m = path.match(/^\/api\/qms\/actions\/(\d+)\/(approve|reject)$/);
    if (m) {
      const a = s.actions.find((x) => x.action_id === Number(m[1]));
      a.status = m[2] === 'approve' ? 'EXECUTED' : 'REJECTED';
      a.decided_by = s.me.displayName;
      return json(route, a);
    }
    if (path === '/api/qms/holds') { return json(route, s.holds); }
    m = path.match(/^\/api\/qms\/holds\/(\d+)\/(brief|resolve)$/);
    if (m) {
      const h = s.holds.find((x) => x.analysis_id === Number(m[1]));
      if (m[2] === 'brief') {
        h.hold_brief = JSON.stringify({ source: 'LLM', summary: ['압력이 3회 튐'], checks: ['압력 센서 배선 확인'] });
      } else {
        s.holds = s.holds.filter((x) => x !== h);
      }
      return json(route, {});
    }
    if (path === '/api/qms/shipment/links') { return json(route, s.links); }
    if (path === '/api/qms/shipment/candidates') {
      return json(route, [{ messageId: 'm-1', equipmentId: 'IMM-01', shotNo: 321, shotTs: '2026-11-15T09:10:00', inspectionResult: 'OK', linkedSerial: null }]);
    }
    if (path === '/api/qms/shipment/confirm') {
      s.links.unshift({ confirmed_serial: body.confirmedSerial, provisional_serial: 'TMP-000123', equipment_id: 'IMM-01', shot_no: 321,
        inspection_result: 'OK', shipment_no: body.shipmentNo || null, confirmed_at: '2026-11-23T12:00:00', confirmed_by: s.me.displayName });
      return json(route, { outcome: 'LINKED' });
    }
    m = path.match(/^\/api\/qms\/shipment\/trace\/(.+)$/);
    if (m) {
      return json(route, { window: '앞뒤 30분', shot: { confirmed_serial: decodeURIComponent(m[1]), equipment_id: 'IMM-01', shot_no: 300,
        shot_ts: '2026-11-15T09:00:00', inspection_result: 'OK', mold_temp: 45.2, barrel_temp: 229.8, injection_pressure: 1201,
        holding_pressure: 600, cushion: 5.1, cycle_time: 32.4, motor_current: 44.8 },
        anomalies: [{ detected_at: '2026-11-15T09:05:00', rule_code: 'XBAR', message: '평균 이동', ncr_nos: 'AI-2026-0110' }] });
    }
    return json(route, { error: 'mock 에 없는 API: ' + method + ' ' + path }, 404);
  });
  return s;
}

module.exports = { install };
