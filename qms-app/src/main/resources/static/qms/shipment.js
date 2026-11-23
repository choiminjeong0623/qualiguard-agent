/* 출하 시리얼 화면 — 백엔드: /api/qms/shipment (임시 시리얼 후보 · 확정 연결 · 추적) */
(function ($) {
  'use strict';

  var API = '/api/qms/shipment';
  var state = { me: null, picked: null };

  // CSRF 헤더·401·로그인 사용자 표시는 qg-common.js 가 맡는다
  var esc = QG.esc;
  function canWrite() { return state.me && state.me.role === 'REVIEWER'; }
  function time(ts) { return ts ? esc(QG.time(ts, true)) : '-'; }
  function result(r) {
    if (r == null) { return QG.tone('neutral', '검사 없음'); }
    return QG.stateTag('inspection', r === 'NG' ? 'NG' : 'OK');
  }
  function msg(text, ok) { $('#msg').text(text).attr('class', 'msg mt-3 mb-0 ' + (ok ? 'ok' : 'err')); }
  function empty(sel, cols, text) { $(sel).html('<tr><td colspan="' + cols + '" class="qg-empty-cell">' + esc(text) + '</td></tr>'); }
  var SENSOR = { mold_temp: ['금형 온도', '℃'], barrel_temp: ['실린더 온도', '℃'], injection_pressure: ['사출 압력', 'bar'],
    holding_pressure: ['보압', 'bar'], cushion: ['쿠션', 'mm'], cycle_time: ['사이클 타임', 's'], motor_current: ['모터 전류', 'A'] };

  function drawCandidates(rows) {
    state.picked = null;
    $('#picked').text('없음 — 후보가 하나면 자동으로 정합니다');
    if (!rows.length) { empty('#cands', 5, '해당 임시 시리얼의 shot 이 없습니다.'); return; }
    $('#cands').html(rows.map(function (c) {
      var usable = !c.linkedSerial && c.inspectionResult !== 'NG';
      var label = c.equipmentId + ' #' + (c.shotNo == null ? '?' : c.shotNo);
      return '<tr><td>' + (usable ? '<input type="radio" class="form-check-input qg-radio-lg" name="pick" value="' + esc(c.messageId) + '" data-label="'
          + esc(label) + '" aria-label="' + esc(label + ' 고르기') + '">' : '') + '</td>'
        + '<td>' + esc(c.equipmentId) + ' #' + esc(c.shotNo == null ? '번호 없음' : c.shotNo) + '</td>'
        + '<td>' + time(c.shotTs) + '</td><td>' + result(c.inspectionResult) + '</td>'
        + '<td>' + (c.linkedSerial ? esc(c.linkedSerial) : '-') + '</td></tr>';
    }).join(''));
  }

  function loadLinks() {
    $.getJSON(API + '/links', { top: 30 }).done(function (rows) {
      if (!rows.length) { empty('#links', 6, '아직 연결이 없습니다.'); return; }
      $('#links').html(rows.map(function (r) {
        return '<tr><td><a href="#" class="qg-mono" data-trace="' + esc(r.confirmed_serial) + '">' + esc(r.confirmed_serial) + '</a></td>'
          + '<td>' + result(r.inspection_result) + '</td>'
          + '<td>' + esc(r.equipment_id) + ' #' + esc(r.shot_no == null ? '?' : r.shot_no) + '</td>'
          + '<td class="qg-mono">' + esc(r.provisional_serial || '-') + '</td><td class="qg-mono">' + esc(r.shipment_no || '-') + '</td>'
          + '<td>' + time(r.confirmed_at) + ' · ' + esc(r.confirmed_by) + '</td></tr>';
      }).join(''));
    });
  }

  function trace(serial) {
    $('#traceSerial').val(serial);
    $.getJSON(API + '/trace/' + encodeURIComponent(serial)).done(function (t) {
      var s = t.shot;
      var vals = Object.keys(SENSOR).map(function (k) {
        return '<div><dt>' + esc(SENSOR[k][0]) + '</dt><dd>' + esc(s[k] == null ? '-' : s[k] + ' ' + SENSOR[k][1]) + '</dd></div>';
      }).join('');
      var an = t.anomalies.length
        ? '<ul class="qg-list-plain">' + t.anomalies.map(function (a) {
            return '<li><span class="muted">' + time(a.detected_at) + '</span> · ' + esc(QG.labelWithCode('rule', a.rule_code)) + ' · ' + esc(a.message)
              + (a.ncr_nos ? ' ' + a.ncr_nos.split(',').map(function (n) {
                  n = $.trim(n); return '<a class="qg-mono" href="index.html#' + encodeURIComponent(n) + '">' + esc(n) + '</a>';
                }).join(', ') : '') + '</li>';
          }).join('') + '</ul>'
        : '<p class="muted mb-0">그 무렵 경보 없음</p>';
      $('#trace').html('<div class="qg-objtitle"><span class="qg-h2 qg-mono">' + esc(s.confirmed_serial) + '</span>' + result(s.inspection_result)
          + (s.arrival_late ? QG.tone('warning', '늦게 도착 · ' + (s.late_status || '')) : '') + '</div>'
          + '<dl class="qg-facts"><div><dt>설비 · shot</dt><dd>' + esc(s.equipment_id) + ' #' + esc(s.shot_no == null ? '?' : s.shot_no) + '</dd></div>'
          + '<div><dt>생산 시각</dt><dd>' + time(s.shot_ts) + '</dd></div></dl>'
          + '<h3 class="qg-h3">공정 값</h3><dl class="qg-facts">' + vals + '</dl>'
          + '<h3 class="qg-h3">그 무렵 경보 <span class="muted">(' + esc(t.window) + ')</span></h3>' + an);
    }).fail(function (xhr) {
      $('#trace').html('<p class="msg err mb-0">' + esc((xhr.responseJSON && xhr.responseJSON.error) || '해당 시리얼의 연결이 없습니다.') + '</p>');
    });
  }

  $('#find').on('submit', function (e) {
    e.preventDefault();
    $.getJSON(API + '/candidates', { serial: $('#provisional').val() }).done(drawCandidates);
  });

  $('#cands').on('change', 'input[name=pick]', function () {
    state.picked = $(this).val();
    $('#picked').text($(this).attr('data-label'));
  });

  $('#confirm').on('submit', function (e) {
    e.preventDefault();
    if (!canWrite()) { msg('조회 전용 계정은 연결할 수 없습니다.', false); return; }
    var body = { confirmedSerial: $('#confirmed').val(), shipmentNo: $('#shipmentNo').val() };
    if (state.picked) { body.messageId = state.picked; } else { body.provisionalSerial = $('#provisional').val(); }
    $.ajax({ method: 'POST', url: API + '/confirm', contentType: 'application/json', data: JSON.stringify(body) })
      .done(function (r) {
        msg(body.confirmedSerial + ' 연결했습니다.' + (r.warning ? ' 주의: ' + r.warning : ''), true);
        loadLinks();
        $('#find').trigger('submit');
      })
      .fail(function (xhr) {
        var r = xhr.responseJSON || {};
        msg(r.message || ('실패 (' + xhr.status + ')'), false);
        if (r.outcome === 'AMBIGUOUS') { drawCandidates(r.candidates); }
      });
  });

  $('#traceForm').on('submit', function (e) { e.preventDefault(); trace($('#traceSerial').val()); });
  var pageTabs = QG.tabs('#page-tabs');
  $('#links').on('click', 'a[data-trace]', function (e) { e.preventDefault(); pageTabs.select('tab-trace'); trace($(this).attr('data-trace')); });

  QG.me().done(function (me) {
    state.me = me;
    if (!canWrite()) { $('#confirmBtn').prop('disabled', true); }
    loadLinks();
  });
}(jQuery));
