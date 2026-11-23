/* 에이전트 조치 화면 — 백엔드: /api/qms/actions (자동 처리 · 승인 대기 · 효과 검증 결과) */
(function ($) {
  'use strict';

  var API = '/api/qms/actions';
  var ACTION_LABEL = {
    HEATER_TEMP_COMPENSATE: '히터 설정 온도 +5℃', COOLING_FLOW_INCREASE: '냉각수 유량 +10%',
    CHECK_RING_REPLACE: '라인 정지·체크 링 교체', SENSOR_RECALIBRATE: '센서 점검·교정',
    MATERIAL_LOT_HOLD: '원재료 로트 보류', EXTRA_INSPECTION: '50 shot 전수 재검사'
  };
  var STATUS_LABEL = {
    WAITING_APPROVAL: '승인 대기', EXECUTED: '효과 확인 중', RESOLVED: '해소', NOT_RESOLVED: '효과 없음 · 재분석',
    ESCALATED: '사람 확인 필요', REJECTED: '반려'
  };
  var STATUS_COLOR = {
    WAITING_APPROVAL: 'bg-yellow-lt', EXECUTED: 'bg-blue-lt', RESOLVED: 'bg-green-lt', NOT_RESOLVED: 'bg-orange-lt',
    ESCALATED: 'bg-red-lt', REJECTED: 'bg-secondary-lt'
  };
  var RISK_COLOR = { LOW: 'bg-green-lt', MEDIUM: 'bg-yellow-lt', HIGH: 'bg-red-lt' };
  var state = { rows: [], filter: '', me: null };

  function csrf() {
    var c = document.cookie.split('; ').find(function (x) { return x.indexOf('XSRF-TOKEN=') === 0; });
    return c ? decodeURIComponent(c.split('=')[1]) : '';
  }
  $.ajaxSetup({ beforeSend: function (xhr, s) { if (s.type !== 'GET') { xhr.setRequestHeader('X-XSRF-TOKEN', csrf()); } } });
  $(document).ajaxError(function (e, xhr) { if (xhr.status === 401) { location.href = 'login.html'; } });
  function esc(s) { return $('<div>').text(s == null ? '' : String(s)).html(); }
  function badge(text, color) { return '<span class="badge ' + color + '">' + esc(text) + '</span>'; }
  function canWrite() { return state.me && state.me.role === 'REVIEWER'; }

  function matches(r, f) {
    if (!f) { return true; }
    if (f === 'AUTO') { return r.mode === 'AUTO'; }
    if (f === 'ESCALATED') { return r.status === 'ESCALATED' || r.status === 'NOT_RESOLVED'; }
    return r.status === f;
  }

  function load() {
    $.getJSON(API, { top: 100 }).done(function (rows) {
      state.rows = rows;
      $('[data-count]').each(function () {
        var f = $(this).attr('data-count');
        $(this).text(rows.filter(function (r) { return matches(r, f); }).length);
      });
      draw();
    }).fail(function (xhr) { $('#msg').text('불러오기 실패 (' + xhr.status + ')').attr('class', 'msg err'); });
  }

  function draw() {
    var rows = state.rows.filter(function (r) { return matches(r, state.filter); });
    if (!rows.length) { $('#rows').html('<tr><td colspan="8" class="text-secondary">조치가 없습니다.</td></tr>'); return; }
    $('#rows').html(rows.map(function (r) {
      var buttons = r.status === 'WAITING_APPROVAL' && canWrite()
        ? '<button class="btn btn-sm btn-primary" data-act="approve" data-id="' + r.action_id + '">승인·실행</button> '
          + '<button class="btn btn-sm btn-outline-danger" data-act="reject" data-id="' + r.action_id + '">반려</button>'
        : '';
      var note = (r.reasons ? '<div class="text-secondary small">' + esc(r.reasons) + '</div>' : '')
        + (r.effect_detail ? '<div class="small">' + esc(r.effect_detail) + '</div>' : '');
      return '<tr><td>' + r.action_id + (r.attempt > 1 ? '<div class="small text-secondary">' + r.attempt + '번째</div>' : '')
        + '</td><td>' + esc(r.equipment_id) + '</td>'
        + '<td>' + esc(r.cause_code) + ' → ' + esc(ACTION_LABEL[r.action_code] || r.action_code) + '</td>'
        + '<td>' + badge(r.risk, RISK_COLOR[r.risk] || '') + '</td>'
        + '<td>' + (r.mode === 'AUTO' ? '에이전트 자동' : '사람 승인' + (r.decided_by ? ' · ' + esc(r.decided_by) : '')) + '</td>'
        + '<td>' + badge(STATUS_LABEL[r.status] || r.status, STATUS_COLOR[r.status] || '') + '</td>'
        + '<td>' + note + '</td><td class="text-nowrap">' + buttons + '</td></tr>';
    }).join(''));
  }

  $('#tiles').on('click', '.tab', function () {
    $('#tiles .tab').removeClass('active');
    $(this).addClass('active');
    state.filter = $(this).attr('data-filter');
    draw();
  });

  $('#rows').on('click', 'button[data-act]', function () {
    var id = $(this).attr('data-id'), act = $(this).attr('data-act');
    $.ajax({ method: 'POST', url: API + '/' + id + '/' + act }).done(function () {
      $('#msg').text('조치 ' + id + (act === 'approve' ? ' 승인 · 실행 표시했습니다. 40 shot 뒤 효과를 확인합니다.' : ' 반려했습니다.'))
        .attr('class', 'msg ok');
      load();
    }).fail(function (xhr) {
      $('#msg').text((xhr.responseJSON && xhr.responseJSON.error) || '실패 (' + xhr.status + ')').attr('class', 'msg err');
    });
  });

  // ── 판단 보류 (로드맵 F2) ─────────────────────────────────────
  var HOLD_REASON = { RECHECK_LIMIT: '재확인 상한 도달', VERIFY_WEAK: '검증 지적 남음' };
  var DECISION_LABEL = { OBSERVE: '관찰', SENSOR_CHECK: '센서 점검', NCR_DRAFT: '부적합 초안' };

  function loadHolds() {
    $.getJSON('/api/qms/holds', { status: 'PENDING', top: 50 }).done(function (rows) {
      $('#hold-count').text(rows.length);
      if (!rows.length) { $('#hold-rows').html('<tr><td colspan="6" class="text-secondary">검토할 판단 보류가 없습니다.</td></tr>'); return; }
      $('#hold-rows').html(rows.map(function (r) {
        var watch = [];
        try { watch = JSON.parse(r.watch_items || '[]') || []; } catch (e) { watch = []; }
        var button = canWrite()
          ? '<div class="input-group input-group-sm"><input class="form-control" placeholder="확인·처리 메모" data-note="' + r.analysis_id + '">'
            + '<button class="btn btn-primary" data-hold="' + r.analysis_id + '">검토 완료</button></div>'
          : '';
        return '<tr><td>' + r.analysis_id + '<div class="small text-secondary">' + esc(r.trigger_type || '') + (r.as_of_shot != null ? ' · shot ' + r.as_of_shot : '') + '</div></td>'
          + '<td>' + esc(r.equipment_id) + '</td>'
          + '<td>' + esc(DECISION_LABEL[r.decision] || r.decision) + '<div class="small text-secondary">' + esc(r.cause_code) + ' · 확신도 ' + esc(r.confidence) + '</div></td>'
          + '<td>' + badge(HOLD_REASON[r.hold_reason] || r.hold_reason, 'bg-red-lt') + '<div class="small">' + esc(r.hold_detail) + '</div></td>'
          + '<td><div class="small">' + esc(r.summary) + '</div>'
          + (watch.length ? '<div class="small text-secondary">확인할 항목: ' + watch.map(esc).join(', ') + '</div>' : '')
          + briefHtml(r) + '</td>'
          + '<td style="min-width:16rem">' + button + '</td></tr>';
      }).join(''));
    }).fail(function (xhr) { $('#hold-rows').html('<tr><td colspan="6" class="text-danger">불러오기 실패 (' + xhr.status + ')</td></tr>'); });
  }

  // 판단 보류 요약: 보류 여부는 규칙이 정하고, LLM 은 검토자가 빨리 읽도록 분석 흐름을 정리만 한다 (ADR-020)
  function briefHtml(r) {
    var b = null;
    try { b = r.hold_brief ? JSON.parse(r.hold_brief) : null; } catch (e) { b = null; }
    if (!b) {
      return canWrite() ? '<button class="btn btn-sm btn-outline-secondary mt-1" data-brief="' + r.analysis_id + '">요약·현장 확인 항목 만들기</button>' : '';
    }
    var label = b.source === 'LLM' ? 'AI 요약' : '기록 요약 (LLM 없이)';
    return '<div class="small mt-2 p-2 bg-azure-lt rounded"><b>' + label + '</b><ul class="mb-1 ps-3">'
      + (b.summary || []).map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>'
      + ((b.checks || []).length ? '<b>현장 확인</b><ul class="mb-0 ps-3">' + b.checks.map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>' : '')
      + '<div class="text-secondary">판단·처리는 검토자가 합니다.</div></div>';
  }

  $('#hold-rows').on('click', 'button[data-brief]', function () {
    var id = $(this).attr('data-brief');
    $(this).prop('disabled', true).text('요약 만드는 중…');
    $.ajax({ method: 'POST', url: '/api/qms/holds/' + id + '/brief' })
      .done(function () { loadHolds(); })
      .fail(function (xhr) { $('#msg').text((xhr.responseJSON && xhr.responseJSON.error) || '요약 실패 (' + xhr.status + ')').attr('class', 'msg err'); loadHolds(); });
  });

  $('#hold-rows').on('click', 'button[data-hold]', function () {
    var id = $(this).attr('data-hold');
    var note = $('input[data-note="' + id + '"]').val();
    $.ajax({ method: 'POST', url: '/api/qms/holds/' + id + '/resolve', contentType: 'application/json', data: JSON.stringify({ note: note }) })
      .done(function () { $('#msg').text('분석 ' + id + ' 판단 보류를 검토 완료로 닫았습니다.').attr('class', 'msg ok'); loadHolds(); })
      .fail(function (xhr) { $('#msg').text((xhr.responseJSON && xhr.responseJSON.error) || '실패 (' + xhr.status + ')').attr('class', 'msg err'); });
  });

  $.getJSON('/api/qms/me').done(function (me) {
    state.me = me;
    $('#me').text(me.displayName + (me.role === 'REVIEWER' ? ' (품질 담당자)' : ' (조회 전용)'));
    load();
    loadHolds();
    setInterval(function () { load(); loadHolds(); }, 10000);   // 효과 검증은 서버가 주기적으로 하므로 화면도 10초마다 새로 읽는다
  });
}(jQuery));
