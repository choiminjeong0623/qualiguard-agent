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
  var state = { rows: [], filter: '', me: null };

  // CSRF 헤더·401·로그인 사용자 표시는 qg-common.js 가 맡는다
  var esc = QG.esc;
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
      var waiting = rows.filter(function (r) { return matches(r, 'WAITING_APPROVAL'); }).length;
      $('#action-count').text(waiting || '').attr('title', waiting ? '승인 대기 ' + waiting + '건' : null)
        .toggleClass('qg-tab-count-attn', waiting > 0);
    }).fail(function (xhr) { msg(QG.errorText(xhr), 'err'); });
  }
  function msg(text, kind) { $('#msg').text(text || '').attr('class', 'msg qg-page-msg ' + (kind || 'info')); }

  function draw() {
    var rows = state.rows.filter(function (r) { return matches(r, state.filter); });
    if (!rows.length) { $('#rows').html('<tr><td colspan="8" class="qg-empty-cell">해당하는 조치가 없습니다.</td></tr>'); return; }
    $('#rows').html(rows.map(function (r) {
      var buttons = r.status === 'WAITING_APPROVAL' && canWrite()
        ? '<button class="btn btn-sm btn-primary" data-act="approve" data-id="' + r.action_id + '">승인·실행</button> '
          + '<button class="btn btn-sm btn-outline-danger" data-act="reject" data-id="' + r.action_id + '">반려</button>'
        : '';
      var note = (r.reasons ? '<div class="muted">' + esc(r.reasons) + '</div>' : '')
        + (r.effect_detail ? '<div>' + esc(r.effect_detail) + '</div>' : '');
      return '<tr' + (r.status === 'WAITING_APPROVAL' ? ' class="qg-row-attn"' : '') + '>'
        + '<td>' + QG.stateTag('action', r.status, STATUS_LABEL[r.status]) + '</td>'
        + '<td>' + QG.stateTag('risk', r.risk) + '</td>'
        + '<td>' + esc(r.equipment_id) + '</td>'
        + '<td class="qg-wrap"><span class="muted">' + esc(QG.label('cause', r.cause_code)) + ' →</span> ' + esc(ACTION_LABEL[r.action_code] || r.action_code) + '</td>'
        + '<td>' + (r.mode === 'AUTO' ? '에이전트 자동' : '사람 승인' + (r.decided_by ? ' · ' + esc(r.decided_by) : '')) + '</td>'
        + '<td class="qg-wrap qg-note-cell">' + note + '</td>'
        + '<td class="text-end qg-mono">' + r.action_id + (r.attempt > 1 ? '<div class="muted">' + r.attempt + '번째</div>' : '') + '</td>'
        + '<td class="text-nowrap text-end">' + buttons + '</td></tr>';
    }).join(''));
  }

  $('#tiles').on('click', '.tab', function () {
    $('#tiles .tab').removeClass('active').attr('aria-pressed', 'false');
    $(this).addClass('active').attr('aria-pressed', 'true');
    state.filter = $(this).attr('data-filter');
    draw();
  });

  // 승인·반려는 설비에 직접 영향을 주므로 대상과 결과를 보여 주고 한 번 더 확인한다
  $('#rows').on('click', 'button[data-act]', function () {
    var id = $(this).attr('data-id'), act = $(this).attr('data-act');
    var r = state.rows.filter(function (x) { return String(x.action_id) === String(id); })[0] || {};
    var what = (r.equipment_id || '') + ' · ' + (ACTION_LABEL[r.action_code] || r.action_code || '');
    QG.confirm(act === 'approve'
      ? { title: '조치 ' + id + ' 승인·실행', ok: '승인·실행', danger: r.risk === 'HIGH',
          body: what + ' 을(를) 실행합니다. ' + (r.risk === 'HIGH' ? '고위험 조치로 라인 정지가 따를 수 있습니다. ' : '') + '40 shot 뒤 효과를 확인합니다.' }
      : { title: '조치 ' + id + ' 반려', ok: '반려', body: what + ' 을(를) 실행하지 않습니다.' })
    .done(function () { $.ajax({ method: 'POST', url: API + '/' + id + '/' + act }).done(function () {
      msg('조치 ' + id + (act === 'approve' ? ' 승인 · 실행 표시했습니다. 40 shot 뒤 효과를 확인합니다.' : ' 반려했습니다.'), 'ok');
      load();
    }).fail(function (xhr) {
      msg(QG.errorText(xhr), 'err');
    }); });
  });

  // ── 판단 보류 (로드맵 F2) ─────────────────────────────────────
  var HOLD_REASON = { RECHECK_LIMIT: '재확인 상한 도달', VERIFY_WEAK: '검증 지적 남음' };

  // 검토자가 메모를 쓰는 중이면 자동 새로 읽기로 지우지 않는다
  function typingHold() {
    return $('#hold-rows [data-note]').filter(function () { return this === document.activeElement || $.trim(this.value); }).length > 0;
  }
  function loadHolds(force) {
    if (!force && typingHold()) { return; }
    $.getJSON('/api/qms/holds', { status: 'PENDING', top: 50 }).done(function (rows) {
      $('#hold-count').text(rows.length || '').toggleClass('qg-tab-count-attn', rows.length > 0);
      if (!rows.length) { $('#hold-rows').html('<tr><td colspan="6" class="qg-empty-cell">검토할 판단 보류가 없습니다.</td></tr>'); return; }
      $('#hold-rows').html(rows.map(function (r) {
        var watch = [];
        try { watch = JSON.parse(r.watch_items || '[]') || []; } catch (e) { watch = []; }
        var button = canWrite()
          ? '<label class="visually-hidden" for="note-' + r.analysis_id + '">분석 ' + r.analysis_id + ' 확인·처리 메모</label>'
            + '<textarea class="form-control form-control-sm" rows="2" id="note-' + r.analysis_id + '" placeholder="확인·처리 메모" data-note="' + r.analysis_id + '"></textarea>'
            + '<button type="button" class="btn btn-sm btn-primary mt-1" data-hold="' + r.analysis_id + '">검토 완료</button>'
          : '<span class="muted">품질 담당자가 검토합니다</span>';
        return '<tr><td>' + QG.tone('danger', HOLD_REASON[r.hold_reason] || r.hold_reason) + '<div class="muted">' + esc(r.hold_detail) + '</div></td>'
          + '<td>' + esc(r.equipment_id) + '</td>'
          + '<td class="qg-mono">#' + r.analysis_id + '<div class="muted">' + esc(r.trigger_type === 'RECHECK' ? '재확인' : (r.trigger_type || '')) + (r.as_of_shot != null ? ' · shot ' + r.as_of_shot : '') + '</div></td>'
          + '<td>' + esc(QG.label('decision', r.decision)) + '<div class="muted">' + esc(QG.label('cause', r.cause_code)) + ' · 확신도 ' + esc(r.confidence) + '</div></td>'
          + '<td class="qg-wrap"><div>' + esc(r.summary) + '</div>'
          + (watch.length ? '<div class="muted">확인할 항목: ' + watch.map(esc).join(', ') + '</div>' : '')
          + briefHtml(r) + '</td>'
          + '<td class="qg-hold-review">' + button + '</td></tr>';
      }).join(''));
    }).fail(function (xhr) { $('#hold-rows').html('<tr><td colspan="6" class="qg-empty-cell msg err">' + esc(QG.errorText(xhr)) + '</td></tr>'); });
  }

  // 판단 보류 요약: 보류 여부는 규칙이 정하고, LLM 은 검토자가 빨리 읽도록 분석 흐름을 정리만 한다 (ADR-020)
  function briefHtml(r) {
    var b = null;
    try { b = r.hold_brief ? JSON.parse(r.hold_brief) : null; } catch (e) { b = null; }
    if (!b) {
      return canWrite() ? '<button type="button" class="btn btn-sm mt-1" data-brief="' + r.analysis_id + '">요약·현장 확인 항목 만들기</button>' : '';
    }
    var label = b.source === 'LLM' ? 'AI 요약' : '기록 요약 (LLM 없이)';
    return '<div class="qg-brief"><b>' + label + '</b><ul>'
      + (b.summary || []).map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>'
      + ((b.checks || []).length ? '<b>현장 확인</b><ul>' + b.checks.map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>' : '')
      + '<div class="text-secondary">판단·처리는 검토자가 합니다.</div></div>';
  }

  $('#hold-rows').on('click', 'button[data-brief]', function () {
    var id = $(this).attr('data-brief');
    $(this).prop('disabled', true).text('요약 만드는 중…');
    $.ajax({ method: 'POST', url: '/api/qms/holds/' + id + '/brief' })
      .done(function () { loadHolds(true); })
      .fail(function (xhr) { msg(QG.errorText(xhr), 'err'); loadHolds(true); });
  });

  $('#hold-rows').on('click', 'button[data-hold]', function () {
    var id = $(this).attr('data-hold');
    var note = $('[data-note="' + id + '"]').val();
    $.ajax({ method: 'POST', url: '/api/qms/holds/' + id + '/resolve', contentType: 'application/json', data: JSON.stringify({ note: note }) })
      .done(function () { msg('분석 ' + id + ' 판단 보류를 검토 완료로 닫았습니다.', 'ok'); $('#note-' + id).val(''); loadHolds(true); })
      .fail(function (xhr) { msg(QG.errorText(xhr), 'err'); });
  });

  QG.tabs('#page-tabs', function () { msg(''); });

  QG.me().done(function (me) {
    state.me = me;
    load();
    loadHolds();
    setInterval(function () { load(); loadHolds(); }, 10000);   // 효과 검증은 서버가 주기적으로 하므로 화면도 10초마다 새로 읽는다
  });
}(jQuery));
