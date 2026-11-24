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

  // ── 판단 보류 (로드맵 F2): 목록(순번·보류 사유·설비·시각) → 상세 #hold/<분석 번호> ──
  var HOLD_REASON = { RECHECK_LIMIT: '재확인 상한 도달', VERIFY_WEAK: '검증 지적 남음' };
  var holds = { rows: [], loaded: false, current: null };

  function reasonText(r) { return HOLD_REASON[r.hold_reason] || r.hold_reason || ''; }
  function holdId() { var m = /^#hold\/(\d+)$/.exec(location.hash); return m ? Number(m[1]) : null; }
  function hmsg(text, kind) { $('#h-msg').text(text || '').attr('class', 'msg ' + (kind || 'info')); }

  // 검토자가 상세에서 메모를 쓰는 중이면 자동 새로 읽기로 다시 그리지 않는다
  function typingHold() {
    var $n = $('#h-note');
    return $n.length > 0 && ($n[0] === document.activeElement || $.trim($n.val()) !== '');
  }

  function loadHolds(force) {
    return $.getJSON('/api/qms/holds', { status: 'PENDING', top: 50 }).done(function (rows) {
      holds.rows = rows;
      holds.loaded = true;
      $('#hold-count').text(rows.length || '').toggleClass('qg-tab-count-attn', rows.length > 0);
      drawHolds();
      if (holdId() && (force || !typingHold())) { drawHold(); }
    }).fail(function (xhr) {
      $('#hold-rows').html('<tr><td colspan="4" class="qg-empty-cell msg err">' + esc(QG.errorText(xhr)) + '</td></tr>');
    });
  }

  function drawHolds() {
    var rows = holds.rows;
    if (!rows.length) { $('#hold-rows').html('<tr><td colspan="4" class="qg-empty-cell">검토할 판단 보류가 없습니다.</td></tr>'); return; }
    $('#hold-rows').html(rows.map(function (r, i) {
      return '<tr class="pick" tabindex="0" data-hold-id="' + r.analysis_id + '" aria-label="' + esc(reasonText(r) + ' · ' + r.equipment_id + ' · 분석 ' + r.analysis_id + ' 열기') + '">'
        + '<td class="v-no">' + (i + 1) + '</td>'
        + '<td>' + QG.tone('danger', reasonText(r)) + '</td>'
        + '<td>' + esc(r.equipment_id) + '</td>'
        + '<td class="v-r">' + esc(QG.time(r.created_at)) + '</td></tr>';
    }).join(''));
  }

  function info(label, value) { return '<div><dt>' + esc(label) + '</dt><dd>' + value + '</dd></div>'; }

  function drawHold() {
    var id = holdId();
    var r = holds.rows.filter(function (x) { return x.analysis_id === id; })[0];
    $('#h-no').text('#' + id);
    if (!r) {
      holds.current = null;
      $('#h-title-text').text('분석 #' + id);
      $('#h-reason').empty();
      $('#h-body').prop('hidden', true);
      $('#h-missing').text('검토 대기 중인 판단 보류가 아닙니다. 이미 검토를 마쳤거나 없는 번호입니다.').prop('hidden', false);
      return;
    }
    holds.current = r;
    $('#h-missing').prop('hidden', true);
    $('#h-body').prop('hidden', false);
    $('#h-title-text').text(reasonText(r) + ' · ' + r.equipment_id);
    $('#h-reason').html(QG.tone('danger', '판단 보류'));

    $('#h-judgement').html(
      info('판단', esc(QG.label('decision', r.decision)))
      + info('원인', esc(QG.label('cause', r.cause_code)))
      + info('확신도', esc(r.confidence != null ? r.confidence + '%' : '–'))
      + info('보류 사유', esc(reasonText(r)) + (r.hold_detail ? ' <small>' + esc(r.hold_detail) + '</small>' : '')));

    var watch = [];
    try { watch = JSON.parse(r.watch_items || '[]') || []; } catch (e) { watch = []; }
    $('#h-summary').text(r.summary || '에이전트 요약이 없습니다.');
    $('#h-watch').text(watch.length ? '확인할 항목: ' + watch.join(', ') : '').prop('hidden', !watch.length);
    $('#h-brief').html(briefHtml(r));

    $('#h-meta').html(
      info('설비', esc(r.equipment_id))
      + info('분석', '<span class="qg-mono">#' + r.analysis_id + '</span>')
      + info('계기', esc(r.trigger_type === 'RECHECK' ? '재확인' : (r.trigger_type || '–')) + (r.as_of_shot != null ? ' <small>shot ' + esc(r.as_of_shot) + '</small>' : ''))
      + info('보류 시각', esc(QG.time(r.created_at) || '–')));

    var keep = $('#h-note').val();
    $('#h-review-body').html(canWrite()
      ? '<label class="form-label" for="h-note">확인·처리 메모</label>'
        + '<textarea class="form-control" rows="3" id="h-note" placeholder="확인·처리 메모"></textarea>'
        + '<div class="v-secfoot"><p id="h-msg" class="msg" role="status"></p><span class="v-sp"></span>'
        + '<button type="button" class="v-sbtn v-pri" id="h-resolve">검토 완료</button></div>'
      : '<p class="muted">품질 담당자가 검토합니다.</p>');
    if (keep) { $('#h-note').val(keep); }
  }

  // 판단 보류 요약: 보류 여부는 규칙이 정하고, LLM 은 검토자가 빨리 읽도록 분석 흐름을 정리만 한다 (ADR-020)
  function briefHtml(r) {
    var b = null;
    try { b = r.hold_brief ? JSON.parse(r.hold_brief) : null; } catch (e) { b = null; }
    if (!b) {
      return canWrite() ? '<button type="button" class="v-sbtn mt-2" id="h-make-brief">요약·현장 확인 항목 만들기</button>' : '';
    }
    var label = b.source === 'LLM' ? 'AI 요약' : '기록 요약 (LLM 없이)';
    return '<div class="qg-brief"><b>' + label + '</b><ul>'
      + (b.summary || []).map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>'
      + ((b.checks || []).length ? '<b>현장 확인</b><ul>' + b.checks.map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>' : '')
      + '<div class="text-secondary">판단·처리는 검토자가 합니다.</div></div>';
  }

  // 목록 → 상세: 행 클릭·Enter. 상세 → 목록: 위치 링크·뒤로 가기
  function openHold(id) { history.pushState(null, '', '#hold/' + id); route(); }
  $('#hold-rows').on('click', 'tr.pick', function () { openHold($(this).attr('data-hold-id')); });
  $('#hold-rows').on('keydown', 'tr.pick', function (e) {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openHold($(this).attr('data-hold-id')); }
  });
  $('#back-to-holds').on('click', function (e) { e.preventDefault(); history.pushState(null, '', '#holds'); route(); });

  $('#view-hold').on('click', '#h-make-brief', function () {
    var id = holds.current.analysis_id;
    $(this).prop('disabled', true).text('요약 만드는 중…');
    $.ajax({ method: 'POST', url: '/api/qms/holds/' + id + '/brief' })
      .always(function () { loadHolds(true); })
      .fail(function (xhr) { hmsg(QG.errorText(xhr), 'err'); });
  });

  $('#view-hold').on('click', '#h-resolve', function () {
    var id = holds.current.analysis_id;
    var note = $('#h-note').val();
    $.ajax({ method: 'POST', url: '/api/qms/holds/' + id + '/resolve', contentType: 'application/json', data: JSON.stringify({ note: note }) })
      .done(function () {
        $('#h-note').val('');
        history.pushState(null, '', '#holds');
        route();
        msg('분석 ' + id + ' 판단 보류를 검토 완료로 닫았습니다.', 'ok');
        loadHolds(true);
      })
      .fail(function (xhr) { hmsg(QG.errorText(xhr), 'err'); $('#h-note').trigger('focus'); });
  });

  var pageTabs;
  function route() {
    var id = holdId();
    $('#view-hold').prop('hidden', !id);
    $('.qg-view').first().prop('hidden', !!id);
    if (id) {
      if (holds.loaded) { drawHold(); }
      $('#h-title').trigger('focus');
    } else if (location.hash === '#holds') {
      pageTabs.select('tab-holds');
    }
  }
  $(window).on('popstate hashchange', route);

  pageTabs = QG.tabs('#page-tabs', function () { msg(''); });
  route();

  QG.me().done(function (me) {
    state.me = me;
    load();
    loadHolds().always(function () { if (holdId()) { drawHold(); } });
    setInterval(function () { load(); loadHolds(); }, 10000);   // 효과 검증은 서버가 주기적으로 하므로 화면도 10초마다 새로 읽는다
  });
}(jQuery));
