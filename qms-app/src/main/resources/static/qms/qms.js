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
    relatedSensors: '관련 센서' };

  var state = { status: 'DRAFT', selected: null, current: null, me: null };

  // 쓰기 요청에는 CSRF 토큰(XSRF-TOKEN 쿠키 값)을 헤더로 보낸다. 로그인이 끊기면(401) 로그인 화면으로.
  function csrf() {
    var c = document.cookie.split('; ').find(function (x) { return x.indexOf('XSRF-TOKEN=') === 0; });
    return c ? decodeURIComponent(c.split('=')[1]) : '';
  }
  $.ajaxSetup({
    beforeSend: function (xhr, settings) {
      if (settings.type && settings.type !== 'GET') { xhr.setRequestHeader('X-XSRF-TOKEN', csrf()); }
    }
  });
  $(document).ajaxError(function (e, xhr) { if (xhr.status === 401) { location.href = 'login.html'; } });
  function canWrite() { return state.me && state.me.role === 'REVIEWER'; }

  function esc(s) { return $('<div>').text(s == null ? '' : String(s)).html(); }
  function time(s) { return s ? s.replace('T', ' ').replace(/\.\d+.*$/, '').replace(/Z$/, '') : ''; }
  function msg(text, kind) { $('#msg').text(text || '').attr('class', 'msg ' + (kind || '')); }
  function errorText(xhr) {
    var body = xhr.responseJSON;
    return (body && body.error) ? body.error : ('요청 실패 (' + xhr.status + ')');
  }
  function send(method, url, body) {
    return $.ajax({ method: method, url: url, contentType: 'application/json',
      data: body ? JSON.stringify(body) : undefined, dataType: 'json' });
  }

  // ── 목록 ─────────────────────────────────────────────────────
  function loadList() {
    $.getJSON(API, { status: state.status, top: 200 }).done(function (rows) {
      var $b = $('#rows').empty();
      if (!rows.length) {
        $b.append('<tr><td colspan="7" class="muted">해당 상태의 부적합이 없습니다.</td></tr>');
        return;
      }
      rows.forEach(function (r) {
        $('<tr class="pick">').attr('data-no', r.ncr_no).toggleClass('selected', r.ncr_no === state.selected)
          .append('<td>' + esc(r.ncr_no) + '</td><td>' + esc(r.equipment_id) + '</td><td title="' + esc(r.title) + '">' + esc(r.title) +
            '</td><td>' + esc(r.cause_code || '') + '</td><td>' + esc(r.confidence == null ? '' : r.confidence) +
            '</td><td>' + esc(r.created_by === 'AGENT' ? 'AI' : r.created_by) + '</td><td>' + esc(time(r.created_at)) + '</td>')
          .appendTo($b);
      });
    }).fail(function (xhr) {
      $('#rows').html('<tr><td colspan="7" class="msg err">' + esc(errorText(xhr)) + '</td></tr>');
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
    $('#d-title').text(d.ncr_no + ' · ' + d.title);
    $('#d-status').text(STATUS_LABEL[d.status] || d.status).attr('class', 'badge ' + d.status);
    var meta = d.equipment_id + ' · 발생 ' + time(d.occurred_at) + ' · 작성 ' + (d.created_by === 'AGENT' ? 'AI 에이전트' : d.created_by);
    if (d.confidence != null) { meta += ' · 확신도 ' + d.confidence; }
    if (d.reviewed_by) { meta += ' · ' + (STATUS_LABEL[d.status] || '') + ' ' + d.reviewed_by + ' (' + time(d.reviewed_at) + ')'; }
    if (d.review_comment) { meta += ' · 의견: ' + d.review_comment; }
    $('#d-meta').text(meta);

    var $f = $('#edit-form');
    FIELDS.forEach(function (k) { $f.find('[name="' + k + '"]').val(d[COLUMN[k] || k] || ''); });
    var editable = d.status === 'DRAFT' && canWrite();
    $f.find('input, textarea, select').not('#reject-reason').prop('disabled', !editable);
    $('#btn-save, #btn-approve, #btn-reject, #reject-reason').prop('disabled', !editable);
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

    $('#a-count').text('(' + d.anomalies.length + '건)');
    $('#anomalies').html(d.anomalies.map(function (a) {
      return '<tr><td>' + esc(a.shot_no) + '</td><td>' + esc(a.rule_code) + '</td><td>' + esc(a.sensor_name) +
        '</td><td>' + esc(a.message) + '</td></tr>';
    }).join('') || '<tr><td colspan="4" class="muted">연결된 경보 없음</td></tr>');

    $('#analyses').html(d.analyses.map(function (g) {
      var r = {};
      try { r = JSON.parse(g.result_json || '{}'); } catch (e) { r = {}; }
      var head = '#' + g.analysis_id + ' · ' + (g.trigger_type === 'RECHECK' ? '재확인' : '경보') +
        (g.as_of_shot != null ? ' (shot ' + g.as_of_shot + ')' : '') + ' · ' + g.decision + ' / ' + (g.cause_code || '') +
        ' · 확신도 ' + (g.confidence == null ? '-' : g.confidence) + ' · ' + Math.round((g.latency_ms || 0) / 1000) + '초';
      return '<div class="analysis"><strong>' + esc(head) + '</strong>' +
        (r.reasoningSummary ? '<p>' + esc(r.reasoningSummary) + '</p>' : '') +
        ((r.similarCases && r.similarCases.length) ? '<p class="muted">참고 사례: ' + esc(r.similarCases.join(', ')) + '</p>' : '') +
        '<pre>' + esc(g.tool_trace || '') + '</pre></div>';
    }).join('') || '<p class="muted">분석 기록 없음 (사람이 작성한 부적합)</p>');

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
    }).join('') || '<li class="muted">승인하면 MES/ERP 로 보낼 이벤트가 만들어집니다.</li>');
    $('#btn-retry').prop('hidden', !(latestFailed && canWrite()));

    $('#history').html(d.history.map(function (h) {
      var line = time(h.created_at) + ' · ' + h.actor + ' · ' + (ACTION_LABEL[h.action] || h.action);
      if (h.comment) { line += ' — ' + h.comment; }
      var changed = '';
      try {
        var c = JSON.parse(h.changes_json || '{}');
        changed = Object.keys(c).map(function (k) { return FIELD_LABEL[k] || k; }).join(', ');
      } catch (e) { changed = ''; }
      return '<li>' + esc(line) + (changed ? ' <span class="muted">(' + esc(changed) + ')</span>' : '') + '</li>';
    }).join('') || '<li class="muted">아직 검토 이력이 없습니다.</li>');
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

  $('#btn-approve').on('click', function () {
    var a = requireActor();
    if (!a) { return; }
    // 고친 내용(확정 원인 등)을 먼저 저장한 뒤 승인한다
    save().done(function () {
      send('POST', API + '/' + encodeURIComponent(state.selected) + '/approve', {})
        .done(function (d) { render(d); msg('승인했습니다. 이 부적합은 다음 분석의 유사 사례로 쓰입니다.', 'ok'); loadList(); loadKb(); })
        .fail(function (xhr) { msg(errorText(xhr), 'err'); });
    });
  });

  $('#btn-reject').on('click', function () {
    var a = requireActor();
    if (!a) { return; }
    var reason = $.trim($('#reject-reason').val());
    if (!reason) { msg('반려 사유를 입력하세요.', 'err'); $('#reject-reason').focus(); return; }
    send('POST', API + '/' + encodeURIComponent(state.selected) + '/reject', { reason: reason })
      .done(function (d) { $('#reject-reason').val(''); render(d); msg('반려했습니다.', 'ok'); loadList(); })
      .fail(function (xhr) { msg(errorText(xhr), 'err'); });
  });

  $('#btn-retry').on('click', function () {
    send('POST', API + '/' + encodeURIComponent(state.selected) + '/integration/retry')
      .done(function (d) { render(d); msg('MES/ERP 로 다시 보냈습니다. 응답을 기다리는 중입니다.', 'ok'); })
      .fail(function (xhr) { msg(errorText(xhr), 'err'); });
  });

  $('#btn-close').on('click', function () {
    var a = requireActor();
    if (!a) { return; }
    var done = $.trim($('#action-taken').val());
    if (!done) { msg('조치 내용을 입력하세요.', 'err'); $('#action-taken').focus(); return; }
    send('POST', API + '/' + encodeURIComponent(state.selected) + '/close', { actionTaken: done })
      .done(function (d) { render(d); msg('조치 완료로 닫았습니다.', 'ok'); loadList(); loadKb(); })
      .fail(function (xhr) { msg(errorText(xhr), 'err'); });
  });

  function loadKb() {
    $.getJSON(API + '/knowledge').done(function (rows) {
      var total = 0, ai = 0;
      rows.forEach(function (r) { total += r.total; ai += r.ai_approved; });
      $('#kb').text('지식저장소 ' + total + '건 (AI 초안 승인 ' + ai + '건)');
    });
  }

  $('.filters').on('click', '.tab', function () {
    $('.tab').removeClass('active');
    $(this).addClass('active');
    state.status = String($(this).data('status'));
    loadList();
  });

  $('#rows').on('click', 'tr.pick', function () { loadDetail($(this).data('no')); });

  $('#btn-logout').on('click', function () {
    send('POST', '/logout').always(function () { location.href = 'login.html?logout'; });
  });

  $.getJSON('/api/qms/me').done(function (me) {
    state.me = me;
    $('#me').text(me.displayName + ' (' + (me.role === 'REVIEWER' ? '품질 담당자' : '조회 전용') + ')');
    loadList();
    loadKb();
  });
}(jQuery));
