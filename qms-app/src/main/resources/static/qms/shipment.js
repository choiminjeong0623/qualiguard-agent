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

  function drawCandidates(rows) {
    state.picked = null;
    $('#picked').text('없음 — 후보가 하나면 자동으로 정합니다');
    if (!rows.length) { $('#cands').html('<tr><td colspan="5" class="text-secondary">해당 임시 시리얼의 shot 이 없습니다.</td></tr>'); return; }
    $('#cands').html(rows.map(function (c) {
      var usable = !c.linkedSerial && c.inspectionResult !== 'NG';
      return '<tr><td>' + (usable ? '<input type="radio" name="pick" value="' + esc(c.messageId) + '" data-label="'
          + esc(c.equipmentId + ' #' + (c.shotNo == null ? '?' : c.shotNo)) + '">' : '') + '</td>'
        + '<td>' + esc(c.equipmentId) + ' #' + esc(c.shotNo == null ? '번호 없음' : c.shotNo) + '</td>'
        + '<td>' + time(c.shotTs) + '</td><td>' + result(c.inspectionResult) + '</td>'
        + '<td>' + (c.linkedSerial ? esc(c.linkedSerial) : '-') + '</td></tr>';
    }).join(''));
  }

  function loadLinks() {
    $.getJSON(API + '/links', { top: 30 }).done(function (rows) {
      if (!rows.length) { $('#links').html('<tr><td colspan="6" class="text-secondary">아직 연결이 없습니다.</td></tr>'); return; }
      $('#links').html(rows.map(function (r) {
        return '<tr><td><a href="#" data-trace="' + esc(r.confirmed_serial) + '">' + esc(r.confirmed_serial) + '</a></td>'
          + '<td>' + esc(r.provisional_serial || '-') + '</td>'
          + '<td>' + esc(r.equipment_id) + ' #' + esc(r.shot_no == null ? '?' : r.shot_no) + '</td>'
          + '<td>' + result(r.inspection_result) + '</td><td>' + esc(r.shipment_no || '-') + '</td>'
          + '<td>' + time(r.confirmed_at) + ' · ' + esc(r.confirmed_by) + '</td></tr>';
      }).join(''));
    });
  }

  function trace(serial) {
    $('#traceSerial').val(serial);
    $.getJSON(API + '/trace/' + encodeURIComponent(serial)).done(function (t) {
      var s = t.shot;
      var vals = ['mold_temp', 'barrel_temp', 'injection_pressure', 'holding_pressure', 'cushion', 'cycle_time', 'motor_current']
        .map(function (k) { return k + ' ' + (s[k] == null ? '-' : s[k]); }).join(' · ');
      var an = t.anomalies.length
        ? '<ul class="mb-0">' + t.anomalies.map(function (a) {
            return '<li>' + time(a.detected_at) + ' · ' + esc(a.rule_code) + ' · ' + esc(a.message)
              + (a.ncr_nos ? ' <span class="badge bg-blue-lt">' + esc(a.ncr_nos) + '</span>' : '') + '</li>';
          }).join('') + '</ul>'
        : '<p class="text-secondary mb-0">그 무렵 경보 없음</p>';
      $('#trace').html('<p class="mb-1"><b>' + esc(s.confirmed_serial) + '</b> ← ' + esc(s.equipment_id) + ' #'
          + esc(s.shot_no == null ? '?' : s.shot_no) + ' (' + time(s.shot_ts) + ') ' + result(s.inspection_result)
          + (s.arrival_late ? ' <span class="badge bg-yellow-lt">늦게 도착 · ' + esc(s.late_status || '') + '</span>' : '')
          + '</p><p class="text-secondary mb-2">' + esc(vals) + '</p>'
          + '<p class="mb-1">경보 (' + esc(t.window) + ')</p>' + an);
    }).fail(function (xhr) {
      $('#trace').html('<p class="msg err mb-0">' + esc((xhr.responseJSON && xhr.responseJSON.error) || '없음') + '</p>');
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
  $('#links').on('click', 'a[data-trace]', function (e) { e.preventDefault(); trace($(this).attr('data-trace')); });

  QG.me().done(function (me) {
    state.me = me;
    if (!canWrite()) { $('#confirmBtn').prop('disabled', true); }
    loadLinks();
  });
}(jQuery));
