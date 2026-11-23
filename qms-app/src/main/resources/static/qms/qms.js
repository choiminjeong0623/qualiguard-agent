/* QMS 부적합 검토 화면 (F-08) — jQuery, 백엔드: /api/qms/ncrs */
(function ($) {
  'use strict';

  var API = '/api/qms/ncrs';
  var FIELDS = ['title', 'phenomenon', 'suspectedCause', 'rootCause', 'evidence', 'recommendedAction',
    'causeCode', 'defectType', 'relatedSensors'];
  var COLUMN = { suspectedCause: 'suspected_cause', rootCause: 'root_cause', recommendedAction: 'recommended_action',
    causeCode: 'cause_code', defectType: 'defect_type', relatedSensors: 'related_sensors' };
  var STATUS_LABEL = { DRAFT: '검토 대기', APPROVED: '승인', REJECTED: '반려', CLOSED: '조치 완료' };
  var ACTION_LABEL = { EDIT: '수정', APPROVE: '승인', REJECT: '반려', CLOSE: '조치 완료' };
  var FIELD_LABEL = { title: '제목', phenomenon: '현상', suspectedCause: '추정 원인', rootCause: '확정 원인',
    evidence: '근거', recommendedAction: '권고 조치', causeCode: '원인 코드', defectType: '불량 유형',
    relatedSensors: '관련 센서', actionOwner: '담당자', actionDueDate: '조치 기한', affectedLots: '영향 로트' };
  // 조치 지정 (로드맵 P2): 화면 이름 → 응답 컬럼
  var ASSIGN = { actionOwner: 'action_owner', actionDueDate: 'action_due_date', affectedLots: 'affected_lots' };

  var state = { status: 'DRAFT', selected: null, current: null, me: null, rows: [] };

  // CSRF 헤더·401 처리·로그아웃·로그인 사용자 표시는 qg-common.js (공통 셸)가 맡는다
  function canWrite() { return state.me && state.me.role === 'REVIEWER'; }

  var esc = QG.esc;
  function time(s) { return QG.time(s, true); }
  function msg(text, kind) { $('#msg').text(text || '').attr('class', 'msg ' + (kind || 'info')); }
  function statusTag(s) { return QG.stateTag('ncr', s); }
  var errorText = QG.errorText;
  function send(method, url, body) {
    return $.ajax({ method: method, url: url, contentType: 'application/json',
      data: body ? JSON.stringify(body) : undefined, dataType: 'json' });
  }

  // ── 목록 ─────────────────────────────────────────────────────
  function loadList() {
    $('#list-title').text(STATUS_LABEL[state.status] || '전체');
    $.getJSON(API, { status: state.status, top: 200 }).done(function (rows) {
      state.rows = rows;
      drawRows();
    }).fail(function (xhr) {
      $('#rows').html('<tr><td colspan="7" class="msg err">' + esc(errorText(xhr)) + '</td></tr>');
    });
    loadCounts();
  }

  // 검색어는 화면에서만 거른다 (번호·설비·제목·원인)
  function drawRows() {
    var q = $.trim($('#search').val() || '').toLowerCase();
    var rows = state.rows.filter(function (r) {
      return !q || [r.ncr_no, r.equipment_id, r.title, r.cause_code].join(' ').toLowerCase().indexOf(q) >= 0;
    });
    var $b = $('#rows').empty();
    if (!rows.length) {
      $b.append('<tr><td colspan="7" class="text-secondary">' + (q ? '검색 결과가 없습니다.' : '해당 상태의 부적합이 없습니다.') + '</td></tr>');
      return;
    }
    rows.forEach(function (r) {
      var no = '<span class="qg-no">' + esc(r.ncr_no) + '</span>' + (state.status === '' && r.status ? ' ' + statusTag(r.status) : '');
      var by = r.created_by === 'AGENT' ? '<span class="badge bg-azure-lt">AI</span>' : esc(r.created_by);
      $('<tr class="pick">').attr('data-no', r.ncr_no).toggleClass('selected', r.ncr_no === state.selected)
        .append('<td>' + no + '</td><td>' + esc(r.equipment_id) + '</td><td class="qg-title-cell" title="' + esc(r.title) + '">' + esc(r.title) +
          '</td><td>' + esc(r.cause_code || '') + '</td><td class="text-end"><span class="qg-conf">' + esc(r.confidence == null ? '' : r.confidence) +
          '</span></td><td>' + by + '</td><td class="text-secondary">' + esc(time(r.created_at)) + '</td>')
        .appendTo($b);
    });
  }

  // 상태 타일의 건수: 전체 목록 한 번으로 센다
  function loadCounts() {
    $.getJSON(API, { status: '', top: 500 }).done(function (rows) {
      var c = { '': rows.length, DRAFT: 0, APPROVED: 0, REJECTED: 0, CLOSED: 0 };
      rows.forEach(function (r) { if (c[r.status] != null) { c[r.status] += 1; } });
      $('[data-count]').each(function () { $(this).text(c[$(this).attr('data-count')]); });
    });
  }

  // ── 상세 ─────────────────────────────────────────────────────
  function loadDetail(no) {
    state.selected = no;
    $('#rows tr').removeClass('selected').filter('[data-no="' + no + '"]').addClass('selected');
    return $.getJSON(API + '/' + encodeURIComponent(no)).done(render).fail(function (xhr) { msg(errorText(xhr), 'err'); });
  }

  function render(d) {
    state.current = d;
    $('#detail').prop('hidden', false);
    $('#empty').prop('hidden', true);
    $('#d-no').text(d.ncr_no);
    $('#d-title').text(d.title);
    $('#d-status').text(STATUS_LABEL[d.status] || d.status).attr('class', 'badge qg-status ' + d.status);
    var meta = [['설비', d.equipment_id], ['발생', time(d.occurred_at)],
      ['작성', d.created_by === 'AGENT' ? 'AI 에이전트' : d.created_by]];
    if (d.confidence != null) { meta.push(['확신도', d.confidence]); }
    if (d.reviewed_by) { meta.push([(STATUS_LABEL[d.status] || '검토') + '자', d.reviewed_by + ' · ' + time(d.reviewed_at)]); }
    if (d.review_comment) { meta.push(['의견', d.review_comment]); }
    // 필수 항목 충족 (로드맵 P2): 빠진 항목을 승인 전에 보여 준다
    if (d.completeness) {
      var c = d.completeness;
      meta.push(['필수 항목', c.passed + '/' + c.total + (c.missing.length ? ' · 빠짐: ' + c.missing.join(', ') : ' · 모두 충족')]);
    }
    $('#d-meta').html(meta.map(function (m) { return '<div><dt>' + esc(m[0]) + '</dt><dd>' + esc(m[1]) + '</dd></div>'; }).join(''));

    var $f = $('#edit-form');
    FIELDS.forEach(function (k) { $f.find('[name="' + k + '"]').val(d[COLUMN[k] || k] || ''); });
    var editable = d.status === 'DRAFT' && canWrite();
    $f.find('input, textarea, select').prop('disabled', !editable);
    $('#btn-save, #btn-approve, #btn-reject').prop('disabled', !editable);
    $('#edit-form > .qg-actions').first().prop('hidden', !editable);
    $('#edit-state').text(editable ? '수정 가능' : '읽기 전용');
    var $a = $('#assign-form');
    Object.keys(ASSIGN).forEach(function (k) { $a.find('[name="' + k + '"]').val(d[ASSIGN[k]] || ''); });
    var assignable = (d.status === 'DRAFT' || d.status === 'APPROVED') && canWrite();
    $a.find('input, button').prop('disabled', !assignable);
    $('#assign-state').text(assignable ? '초안·승인 상태에서 지정 가능' : '읽기 전용');
    var inKb = d.status === 'APPROVED' || d.status === 'CLOSED';
    $('#d-kb').prop('hidden', !inKb);
    $('#close-box').prop('hidden', d.status !== 'APPROVED' || !canWrite());
    $('#action-taken').prop('disabled', false).val('');
    if (!canWrite()) {
      msg('조회 전용 계정입니다. 수정·승인·반려는 품질 담당자 계정으로 할 수 있습니다.');
    } else {
      msg(editable ? '' : (d.status === 'APPROVED' ? '조치를 마치면 조치 내용을 적고 [조치 완료]를 누르세요.'
        : '초안(검토 대기) 상태에서만 수정·승인·반려할 수 있습니다.'));
    }

    $('#a-count').text(d.anomalies.length + '건');
    $('#g-count').text(d.analyses.length + '건');
    $('#anomalies').html(d.anomalies.map(function (a) {
      return '<tr><td>' + esc(a.shot_no) + '</td><td>' + esc(a.rule_code) + '</td><td>' + esc(a.sensor_name) +
        '</td><td>' + esc(a.message) + '</td></tr>';
    }).join('') || '<tr><td colspan="4" class="text-secondary">연결된 경보 없음</td></tr>');

    $('#analyses').html(d.analyses.map(function (g) {
      var r = {};
      try { r = JSON.parse(g.result_json || '{}'); } catch (e) { r = {}; }
      var head = '<strong>#' + esc(g.analysis_id) + '</strong>' +
        '<span class="badge ' + (g.trigger_type === 'RECHECK' ? 'bg-purple-lt">재확인' : 'bg-orange-lt">경보') + '</span>' +
        '<span class="badge bg-secondary-lt">' + esc(g.decision) + '</span>' + verifyTag(g) +
        '<span>' + esc((g.cause_code || '') + (g.as_of_shot != null ? ' · shot ' + g.as_of_shot : '') +
          ' · 확신도 ' + (g.confidence == null ? '-' : g.confidence) + ' · ' + Math.round((g.latency_ms || 0) / 1000) + '초') + '</span>';
      return '<div class="analysis"><div class="analysis-head">' + head + '</div>' +
        (r.reasoningSummary ? '<p>' + esc(r.reasoningSummary) + '</p>' : '') +
        ((r.similarCases && r.similarCases.length) ? '<p class="text-secondary">참고 사례: ' + esc(r.similarCases.join(', ')) + '</p>' : '') +
        verifyNotes(g) + pipelineNotes(g) +
        '<pre>' + esc(g.tool_trace || '') + '</pre></div>';
    }).join('') || '<p class="text-secondary mb-0">분석 기록 없음 (사람이 작성한 부적합)</p>');

    var ACK = { RECEIVED: '수신 확인', PROCESSED: '처리 완료', FAILED: '실패' };
    var latestFailed = false;
    var events = d.integration || [];
    $('#integration').html(events.map(function (o, i) {
      var state = o.published_at ? ('전송됨 ' + time(o.published_at)) : ('전송 대기' + (o.attempts ? ' (시도 ' + o.attempts + '회)' : ''));
      var line = '<strong>' + esc((o.event_type === 'NCR_APPROVED' ? '승인 이벤트' : o.event_type) + ' · ' + state) + '</strong>';
      if (o.last_error && !o.published_at) { line += ' · ' + esc('오류: ' + o.last_error); }
      var acks = (d.integrationStatus || []).filter(function (s) { return s.event_id === o.event_id; });
      line += acks.length ? '<ul class="acks">' + acks.map(function (s) {
        var t = s.system_name + ': ' + (ACK[s.status] || s.status) + (s.reference_no ? ' ' + s.reference_no : '') +
          (s.message ? ' — ' + s.message : '') + ' (' + time(s.updated_at) + ')';
        return '<li class="ack ' + s.status + '">' + esc(t) + '</li>';
      }).join('') + '</ul>' : (o.published_at ? ' · ' + esc('MES/ERP 응답 대기') : '');
      if (i === events.length - 1) { latestFailed = acks.some(function (s) { return s.status === 'FAILED'; }); }
      return '<li>' + line + '</li>';
    }).join('') || '<li class="text-secondary">승인하면 MES/ERP 로 보낼 이벤트가 만들어집니다.</li>');
    $('#btn-retry').prop('hidden', !(latestFailed && canWrite()));
    drawFlow(d, events[events.length - 1]);

    $('#history').html(d.history.map(function (h) {
      var line = time(h.created_at) + ' · ' + h.actor + ' · ' + (ACTION_LABEL[h.action] || h.action);
      if (h.comment) { line += ' — ' + h.comment; }
      var changed = '';
      try {
        var c = JSON.parse(h.changes_json || '{}');
        changed = Object.keys(c).map(function (k) { return FIELD_LABEL[k] || k; }).join(', ');
      } catch (e) { changed = ''; }
      return '<li>' + esc(line) + (changed ? ' <span class="muted">(' + esc(changed) + ')</span>' : '') + '</li>';
    }).join('') || '<li class="text-secondary">아직 검토 이력이 없습니다.</li>');
  }

  // 승인 → 전송 → MES → ERP 진행 단계 (가장 최근 이벤트 기준). 지난 단계까지 채우고, 실패한 시스템은 빨갛게.
  function drawFlow(d, ev) {
    var all = d.integrationStatus || [];
    var events = d.integration || [];
    function sys(name) {
      // 가장 최근 이벤트부터 거슬러 올라가며 그 시스템의 응답을 찾는다 (재전송이 한 시스템에만 간 경우)
      var a = null;
      for (var i = events.length - 1; i >= 0 && !a; i--) {
        a = all.filter(function (s) { return s.event_id === events[i].event_id && s.system_name === name; })[0] || null;
      }
      if (!a) { return { done: false, label: name + ' 응답 대기' }; }
      var L = { RECEIVED: '수신 확인', PROCESSED: '처리 완료', FAILED: '실패' };
      return { done: a.status !== 'FAILED', failed: a.status === 'FAILED', label: name + ' ' + (L[a.status] || a.status) };
    }
    var approved = d.status === 'APPROVED' || d.status === 'CLOSED';
    var steps = [
      { done: approved, label: approved ? '승인됨' : '승인 전' },
      { done: !!(ev && ev.published_at), label: ev && ev.published_at ? 'Kafka 전송' : '전송 대기' },
      sys('MES'), sys('ERP')
    ];
    $('#flow').html(steps.map(function (s) {
      return '<li class="qg-step' + (s.failed ? ' failed' : (s.done ? ' done' : '')) + '">' + esc(s.label) + '</li>';
    }).join(''));
  }

  // 검증 에이전트 결과 (ADR-016): 통과 / 재분석 뒤 통과 / 근거 부족
  function verifyTag(g) {
    if (!g.verify_status) { return ''; }
    var retried = (g.attempts || 1) > 1;
    var cls = g.verify_status === 'PASS' ? (retried ? 'bg-teal-lt' : 'bg-green-lt') : 'bg-red-lt';
    var text = g.verify_status === 'PASS' ? (retried ? '검증 통과 (재분석 ' + (g.attempts - 1) + '회)' : '검증 통과')
      : '근거 부족' + (retried ? ' (재분석 ' + (g.attempts - 1) + '회 후)' : '');
    return '<span class="badge ' + cls + '">' + esc(text) + '</span>';
  }
  function verifyNotes(g) {
    var v = {};
    try { v = JSON.parse(g.verify_json || '{}'); } catch (e) { v = {}; }
    var items = (v.fails || []).map(function (f) { return '<li class="text-danger">' + esc(f) + '</li>'; })
      .concat((v.warns || []).map(function (w) { return '<li class="text-secondary">' + esc(w) + '</li>'; }));
    return items.length ? '<ul class="qg-verify">' + items.join('') + '</ul>' : '';
  }

  // 역할 분리형 파이프라인 (ADR-016): 분류 결과와 에이전트 단계 기록
  var STATE_LABEL = { TRIAGED: '분류', ANALYZING: '원인 분석', VERIFYING: '검증', WRITING: '초안 작성',
    AWAITING_APPROVAL: '승인 대기', CLOSED: '종결', FAILED: '실패' };
  var REPORT_LABEL = { ANALYSIS_TEXT: '분석 문장', LOCAL: '로컬 모델', TEMPLATE: '근거 표', TEMPLATE_FALLBACK: '근거 표 (로컬 실패)' };
  function pipelineNotes(g) {
    var p;
    try { p = JSON.parse(g.pipeline_json || 'null'); } catch (e) { p = null; }
    if (!p) { return ''; }
    var t = p.triage || {};
    var head = '<div class="qg-pipe-head"><span class="badge ' +
      ({ HIGH: 'bg-red-lt', MEDIUM: 'bg-yellow-lt', LOW: 'bg-secondary-lt' }[t.severity] || 'bg-secondary-lt') + '">' +
      esc('분류 ' + (t.severity || '-') + ' · ' + (t.type || '-')) + '</span>' +
      (p.report ? '<span class="badge bg-azure-lt">' + esc('초안 문장: ' + (REPORT_LABEL[p.report.mode] || p.report.mode) +
        ' · 클라우드 ' + (p.report.cloudCalls || 0) + '회') + '</span>' : '') + '</div>';
    // 기록은 "그 단계에 들어간 시각"과 "앞 단계의 결과"를 담는다.
    // 화면에는 단계마다 걸린 시간(다음 기록까지)과 그 단계의 결과(다음 기록의 detail)를 보이고, 마지막 줄에 합계를 보인다.
    var tl = p.timeline || [];
    var steps = tl.map(function (s, i) {
      var next = tl[i + 1];
      var text = next
        ? ((next.atMs - s.atMs) / 1000).toFixed(1) + '초' + (next.detail ? ' · ' + next.detail : '')
        : '합계 ' + (s.atMs / 1000).toFixed(1) + '초' + (s.state === 'FAILED' && s.detail ? ' · ' + s.detail : '');
      return '<li class="' + (s.state === 'FAILED' ? 'failed' : '') + '"><span>' + esc(STATE_LABEL[s.state] || s.state) +
        '</span><span class="muted">' + esc(text) + '</span></li>';
    }).join('');
    return '<div class="qg-pipe">' + head + (steps ? '<ol class="qg-pipe-steps">' + steps + '</ol>' : '') + '</div>';
  }

  function editedFields() {
    var out = {};
    var $f = $('#edit-form');
    FIELDS.forEach(function (k) { out[k] = $f.find('[name="' + k + '"]').val(); });
    return out;
  }

  // ── 동작 ─────────────────────────────────────────────────────
  // 검토자는 로그인한 사용자 (서버가 세션에서 정한다)
  function requireActor() {
    if (!canWrite()) { msg('조회 전용 계정은 수정·승인·반려할 수 없습니다.', 'err'); return null; }
    return state.me.displayName;
  }

  function save() {
    var a = requireActor();
    if (!a) { return $.Deferred().reject().promise(); }
    return send('PUT', API + '/' + encodeURIComponent(state.selected), { fields: editedFields() })
      .done(function (d) { render(d); msg('저장했습니다.', 'ok'); loadList(); })
      .fail(function (xhr) { msg(errorText(xhr), 'err'); });
  }

  $('#edit-form').on('submit', function (e) { e.preventDefault(); save(); });

  // 조치 담당자·기한·영향 로트 저장 (로드맵 P2). 빈 칸으로 저장하면 그 값을 지운다.
  $('#assign-form').on('submit', function (e) {
    e.preventDefault();
    if (!requireActor()) { return; }
    var body = {};
    Object.keys(ASSIGN).forEach(function (k) { body[k] = $('#assign-form [name="' + k + '"]').val(); });
    send('PUT', API + '/' + encodeURIComponent(state.selected) + '/assignment', body)
      .done(function (d) { render(d); msg('조치 지정을 저장했습니다.', 'ok'); })
      .fail(function (xhr) { msg(errorText(xhr), 'err'); });
  });

  $('#btn-approve').on('click', function () {
    var a = requireActor();
    if (!a) { return; }
    var no = state.selected;
    if (!$.trim($('#edit-form [name="rootCause"]').val())) {
      msg('확정 원인을 입력해야 승인할 수 있습니다.', 'err');
      $('#edit-form [name="rootCause"]').attr('aria-invalid', 'true').trigger('focus');
      return;
    }
    // 승인은 MES/ERP 로 전송되고 지식저장소에 들어가므로 한 번 더 확인한다. 고친 내용을 먼저 저장한 뒤 승인한다
    QG.confirm({ title: no + ' 승인', ok: '승인',
      body: '승인하면 MES 작업 지시·ERP 품질 통지로 전송되고, 다음 분석의 유사 사례로 쓰입니다. 승인 뒤에는 내용을 고칠 수 없습니다.' })
    .done(function () { save().done(function () {
      send('POST', API + '/' + encodeURIComponent(state.selected) + '/approve', {})
        .done(function (d) { render(d); msg('승인했습니다. 이 부적합은 다음 분석의 유사 사례로 쓰입니다.', 'ok'); loadList(); loadKb(); })
        .fail(function (xhr) { msg(errorText(xhr), 'err'); });
    }); });
  });

  // 반려: 사유를 대화상자에서 받는다 (사유 없이는 보낼 수 없음)
  $('#btn-reject').on('click', function () {
    var a = requireActor();
    if (!a) { return; }
    var no = state.selected;
    QG.confirm({ title: no + ' 반려', ok: '반려', danger: true,
      body: '반려한 초안은 지식저장소에 들어가지 않고 다시 고칠 수 없습니다.',
      input: { label: '반려 사유', placeholder: '반려 사유', required: true, maxlength: 1000, error: '반려 사유를 입력하세요.' } })
    .done(function (reason) {
      send('POST', API + '/' + encodeURIComponent(no) + '/reject', { reason: reason })
        .done(function (d) { render(d); msg('반려했습니다.', 'ok'); loadList(); })
        .fail(function (xhr) { msg(errorText(xhr), 'err'); });
    });
  });

  $('#btn-retry').on('click', function () {
    QG.confirm({ title: 'MES/ERP 연계 다시 보내기', ok: '다시 보내기',
      body: '실패한 시스템에 승인 이벤트를 다시 보냅니다. 이미 처리한 시스템은 같은 이벤트를 중복으로 처리하지 않습니다.' })
    .done(function () { send('POST', API + '/' + encodeURIComponent(state.selected) + '/integration/retry')
      .done(function (d) { render(d); msg('MES/ERP 로 다시 보냈습니다. 응답을 기다리는 중입니다.', 'ok'); })
      .fail(function (xhr) { msg(errorText(xhr), 'err'); }); });
  });

  $('#btn-close').on('click', function () {
    var a = requireActor();
    if (!a) { return; }
    var done = $.trim($('#action-taken').val());
    if (!done) { msg('조치 내용을 입력하세요.', 'err'); $('#action-taken').focus(); return; }
    var no = state.selected;
    QG.confirm({ title: no + ' 조치 완료', ok: '조치 완료', body: '조치 완료로 닫으면 더 이상 고칠 수 없습니다. 실제로 한 조치: ' + done })
    .done(function () { send('POST', API + '/' + encodeURIComponent(state.selected) + '/close', { actionTaken: done })
      .done(function (d) { render(d); msg('조치 완료로 닫았습니다.', 'ok'); loadList(); loadKb(); })
      .fail(function (xhr) { msg(errorText(xhr), 'err'); }); });
  });

  function loadKb() {
    $.getJSON(API + '/knowledge').done(function (rows) {
      var total = 0, ai = 0;
      rows.forEach(function (r) { total += r.total; ai += r.ai_approved; });
      $('#kb').prop('hidden', false).text('지식저장소 ' + total + '건 (AI 초안 승인 ' + ai + '건)');
      // AI 초안 검토 지표 (로드맵 P3): 결정된 초안 가운데 반려·그대로 승인한 비율
      $.getJSON(API + '/review-stats').done(function (s) {
        if (!s.decided) { return; }
        var pct = function (x) { return x == null ? '-' : Math.round(x * 100) + '%'; };
        $('#kb').text($('#kb').text() + ' · AI 초안 ' + s.decided + '건 중 그대로 승인 ' + pct(s.asIsRate)
          + ', 반려 ' + pct(s.rejectionRate));
      });
      // 필수 항목 충족 (로드맵 P2): AI 보고서 가운데 모든 항목을 채운 비율
      $.getJSON(API + '/completeness').done(function (c) {
        if (!c.count) { return; }
        $('#kb').text($('#kb').text() + ' · 필수 항목 모두 충족 ' + Math.round(c.completeRate * 100) + '%');
      });
    });
  }

  $('#tiles').on('click', '.tab', function () {
    $('#tiles .tab').removeClass('active').attr('aria-selected', 'false');
    $(this).addClass('active').attr('aria-selected', 'true');
    state.status = String($(this).data('status'));
    loadList();
  });

  $('#rows').on('click', 'tr.pick', function () { loadDetail($(this).data('no')); });
  $('#search').on('input', drawRows);

  // 아코디언 열고 닫기 (Bootstrap JS 를 따로 싣지 않는다)
  $('#more').on('click', '[data-qg-toggle]', function () {
    var $p = $('#' + $(this).attr('data-qg-toggle'));
    var open = $p.prop('hidden');
    $p.prop('hidden', !open);
    $(this).toggleClass('collapsed', !open).attr('aria-expanded', String(open));
  });

  QG.me().done(function (me) {
    state.me = me;
    loadList();
    loadKb();
  });
}(jQuery));
