/* QMS 부적합 검토 화면 (F-08, v3 테마) — jQuery, 백엔드: /api/qms/ncrs
   목록: 검토 현황(지표 + 요약) → 거르개 한 줄 + 검색 한 줄 → 표(체크·순번, 페이지 10/30/50건) → 엑셀·CSV
   상세: 두 번째 띠(제목·내려받기·처리 단계·요약) → 탭 4개(검토·확정 / 분류·조치 지정 / 근거 / 이력) | 기본 정보 → 동작 바 */
(function ($) {
  'use strict';

  var API = '/api/qms/ncrs';
  var COLUMN = { suspectedCause: 'suspected_cause', rootCause: 'root_cause', recommendedAction: 'recommended_action',
    causeCode: 'cause_code', defectType: 'defect_type', relatedSensors: 'related_sensors' };
  var STATUS_LABEL = { DRAFT: '검토 대기', APPROVED: '승인', REJECTED: '반려', CLOSED: '조치 완료' };
  var ACTION_LABEL = { EDIT: '수정', APPROVE: '승인', REJECT: '반려', CLOSE: '조치 완료' };
  var FIELD_LABEL = { title: '제목', phenomenon: '현상', suspectedCause: '추정 원인', rootCause: '확정 원인',
    evidence: '근거', recommendedAction: '권고 조치', causeCode: '원인 코드', defectType: '불량 유형',
    relatedSensors: '관련 센서', actionOwner: '담당자', actionDueDate: '조치 기한', affectedLots: '영향 로트' };
  // 조치 지정 (로드맵 P2): 화면 이름 → 응답 컬럼
  var ASSIGN = { actionOwner: 'action_owner', actionDueDate: 'action_due_date', affectedLots: 'affected_lots' };
  // 필수 항목(NcrCompleteness) 가운데 분류·조치 지정 탭에서 채우는 것
  var ASSIGN_ITEMS = ['제목', '원인 코드', '불량 유형', '관련 센서', '영향 범위', '담당자', '조치 기한'];

  var state = { status: 'DRAFT', selected: null, current: null, me: null, rows: [], all: [],
    page: 1, size: 10, query: '', checked: {} };

  // CSRF 헤더·401 처리·로그아웃·로그인 사용자 표시는 qg-common.js (공통 셸)가 맡는다
  function canWrite() { return state.me && state.me.role === 'REVIEWER'; }

  var esc = QG.esc;
  function time(s) { return QG.time(s, true); }
  function shortTime(s) { return QG.time(s).substring(5); }   // 11-15 16:41
  function msg(text, kind) { $('#msg').text(text || '').attr('class', 'msg ' + (kind || 'info')); }
  function statusTag(s) { return QG.stateTag('ncr', s); }
  var errorText = QG.errorText;
  function send(method, url, body) {
    return $.ajax({ method: method, url: url, contentType: 'application/json',
      data: body ? JSON.stringify(body) : undefined, dataType: 'json' });
  }
  function pctText(c) {
    if (c == null || c === '') { return ''; }
    var n = Number(c);
    if (isNaN(n)) { return String(c); }
    return Math.round(n <= 1 ? n * 100 : n) + '%';
  }
  function nowHm() { var d = new Date(); return (d.getHours() < 10 ? '0' : '') + d.getHours() + ':' + (d.getMinutes() < 10 ? '0' : '') + d.getMinutes(); }
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  function toast(text) {
    var t = document.getElementById('toast');
    t.textContent = text;
    t.classList.add('v-on');
    clearTimeout(t._h);
    t._h = setTimeout(function () { t.classList.remove('v-on'); }, 2600);
  }

  // ── 요약: 받은 기록으로 문장을 만든다 (LLM 호출 없음). 지난번과 데이터가 달라졌으면 만드는 동작을 보여 준다 ──
  function summary(box, key, sig, html) {
    var $b = $(box), $text = $b.find('[data-r=text]'), $state = $b.find('[data-r=state]');
    var store = 'qg.summary.' + key, prev = null;
    try { prev = localStorage.getItem(store); } catch (e) { prev = null; }
    try { localStorage.setItem(store, sig); } catch (e) { /* 저장 못 해도 요약은 보인다 */ }
    clearTimeout($b.data('t'));
    if (prev === sig || reduced) {
      $b.removeClass('v-busy v-typing').attr('aria-busy', 'false');
      $text.html(html);
      $state.text((prev === sig ? '변경 없음 · ' : '새로 만듦 · ') + nowHm());
      return;
    }
    // 바뀐 데이터: 뼈대 → 한 글자씩 → 끝
    $b.addClass('v-busy').removeClass('v-typing').attr('aria-busy', 'true');
    $text.empty();
    $state.text('요약 만드는 중');
    var plain = $('<div>').html(html).text(), i = 0;
    $b.data('t', setTimeout(function step() {
      $b.addClass('v-typing');
      i = Math.min(plain.length, i + 3);
      $text.text(plain.substring(0, i)).append('<span class="v-caret"></span>');
      if (i < plain.length) { $b.data('t', setTimeout(step, 16)); return; }
      $b.removeClass('v-busy v-typing').addClass('v-fresh').attr('aria-busy', 'false');
      $text.html(html);
      $state.text('새로 만듦 · ' + nowHm());
      setTimeout(function () { $b.removeClass('v-fresh'); }, 1700);
    }, 700));
  }
  function mark(s) { return '<mark>' + esc(s) + '</mark>'; }

  // 숫자가 0 에서 올라가며 보인다 (동작 줄이기면 바로)
  function countUp($el, to, suffix) {
    suffix = suffix || '';
    if (reduced || isNaN(to)) { $el.text(to + suffix); return; }
    var t0 = null;
    function f(ts) {
      if (!t0) { t0 = ts; }
      var p = Math.min(1, (ts - t0) / 700);
      $el.text(Math.round(to * (1 - Math.pow(1 - p, 3))) + suffix);
      if (p < 1) { requestAnimationFrame(f); }
    }
    requestAnimationFrame(f);
  }
  function bar($el, pct) { $el.css('width', '0%'); requestAnimationFrame(function () { requestAnimationFrame(function () { $el.css('width', pct + '%'); }); }); }

  // ── 목록 ────────────────────────────────────────────────────
  var sort = { key: 'created_at', dir: -1 };
  var visible = [];   // 거르개·검색·정렬을 거친 목록 (페이지 나누기 전)

  function loadList() {
    $('#list-title').text((STATUS_LABEL[state.status] || '전체') + ' 부적합 목록');
    var req = $.getJSON(API, { status: state.status, top: 500 }).done(function (rows) {
      state.rows = rows;
      state.checked = {};
      fillFilters();
      drawRows();
      $('#hero-time').text(nowHm());
    }).fail(function (xhr) {
      $('#rows').html('<tr><td colspan="9" class="qg-empty-cell msg err">' + esc(errorText(xhr)) + '</td></tr>');
    });
    loadCounts();
    return req;
  }

  // 원인·설비 거르개: 지금 상태의 목록에 있는 값만 고를 수 있게 채운다 (고른 값은 남긴다)
  function fillFilters() {
    function fill(sel, key, all, text) {
      var $s = $(sel), cur = $s.val();
      var vals = state.rows.map(function (r) { return r[key]; }).filter(function (v, i, a) { return v && a.indexOf(v) === i; }).sort();
      $s.html('<option value="">' + all + '</option>' + vals.map(function (v) {
        return '<option value="' + esc(v) + '">' + esc(text(v)) + '</option>';
      }).join(''));
      $s.val(vals.indexOf(cur) >= 0 ? cur : '');
    }
    fill('#f-cause', 'cause_code', '원인 전체', function (v) { return QG.label('cause', v); });
    fill('#f-equip', 'equipment_id', '설비 전체', function (v) { return v; });
  }

  // 검색어(검색 단추·Enter 로 적용)와 거르개는 화면에서만 적용한다
  function drawRows() {
    var q = state.query.toLowerCase(), key = $('#f-key').val();
    var cause = $('#f-cause').val(), equip = $('#f-equip').val();
    visible = state.rows.filter(function (r) {
      if (cause && r.cause_code !== cause) { return false; }
      if (equip && r.equipment_id !== equip) { return false; }
      if (!q) { return true; }
      var hay = key ? [r[key]] : [r.ncr_no, r.equipment_id, r.title, r.cause_code, QG.label('cause', r.cause_code)];
      return hay.join(' ').toLowerCase().indexOf(q) >= 0;
    });
    visible.sort(function (a, b) {
      var x = a[sort.key], y = b[sort.key];
      if (x == null) { return 1; } if (y == null) { return -1; }
      return (x < y ? -1 : x > y ? 1 : 0) * sort.dir;
    });
    var pages = Math.max(1, Math.ceil(visible.length / state.size));
    state.page = Math.min(state.page, pages);
    var from = (state.page - 1) * state.size, slice = visible.slice(from, from + state.size);
    $('#list-count').text(visible.length ? visible.length + '건 중 ' + (from + 1) + '–' + (from + slice.length) : '0건');
    $('.qg-table-list th[data-sort]').each(function () {
      var k = $(this).attr('data-sort');
      $(this).attr('aria-sort', k === sort.key ? (sort.dir > 0 ? 'ascending' : 'descending') : null);
    });
    var $b = $('#rows').empty();
    if (!slice.length) {
      $b.append('<tr><td colspan="9" class="qg-empty-cell">' +
        (q || cause || equip ? '조건에 맞는 부적합이 없습니다.' : '해당 상태의 부적합이 없습니다.') + '</td></tr>');
    }
    slice.forEach(function (r, k) {
      $('<tr class="pick" tabindex="0">').attr('data-no', r.ncr_no).toggleClass('v-sel', !!state.checked[r.ncr_no])
        .append('<td class="v-ck"><input type="checkbox" class="v-rowck" aria-label="' + esc(r.ncr_no) + ' 선택"' +
          (state.checked[r.ncr_no] ? ' checked' : '') + '></td><td class="v-no">' + (from + k + 1) + '</td>' +
          '<td class="v-tt"><div class="v-t1">' + esc(r.title) + '</div></td><td class="qg-mono">' + esc(r.ncr_no) + '</td>' +
          '<td>' + statusTag(r.status) + '</td><td>' + esc(r.equipment_id) + '</td><td>' + esc(QG.label('cause', r.cause_code)) + '</td>' +
          '<td class="v-r">' + esc(pctText(r.confidence)) + '</td><td class="v-r">' + esc(shortTime(r.created_at)) + '</td>')
        .appendTo($b);
    });
    drawPager(pages);
    syncChecks();
  }

  function drawPager(pages) {
    var h = '<button type="button" data-p="' + (state.page - 1) + '"' + (state.page === 1 ? ' disabled' : '') + ' aria-label="이전 페이지">‹</button>';
    for (var i = 1; i <= pages; i++) {
      h += '<button type="button" data-p="' + i + '"' + (i === state.page ? ' aria-current="page"' : '') + ' aria-label="' + i + '쪽">' + i + '</button>';
    }
    h += '<button type="button" data-p="' + (state.page + 1) + '"' + (state.page === pages ? ' disabled' : '') + ' aria-label="다음 페이지">›</button>';
    $('#pager').html(h);
  }

  // 체크: 페이지를 넘겨도 고른 건은 남는다. 머리 체크는 이 페이지만
  function syncChecks() {
    var $cks = $('#rows .v-rowck'), n = $cks.filter(':checked').length;
    $('#ck-all').prop({ checked: n > 0 && n === $cks.length, indeterminate: n > 0 && n < $cks.length });
    var total = Object.keys(state.checked).length;
    $('#selcount').text(total ? '선택 ' + total + '건' : '');
  }

  // 상태 건수·검토 대기 정보·담당자 후보: 전체 목록 한 번으로
  function loadCounts() {
    $.getJSON(API, { status: '', top: 500 }).done(function (rows) {
      state.all = rows;
      var c = { '': rows.length, DRAFT: 0, APPROVED: 0, REJECTED: 0, CLOSED: 0 };
      rows.forEach(function (r) { if (c[r.status] != null) { c[r.status] += 1; } });
      $('#f-status option').each(function () { $(this).text($(this).attr('data-label') + ' (' + c[$(this).val()] + ')'); });
      countUp($('[data-count="DRAFT"]'), c.DRAFT);
      $('[data-count="DRAFT"]').attr('data-n', c.DRAFT);
      $('#hero-draft').html(c.DRAFT ? QG.tone('warning', '검토 대기 ' + c.DRAFT) : '');
      QG.badge('ncr', c.DRAFT);
      var drafts = rows.filter(function (r) { return r.status === 'DRAFT'; })
        .sort(function (a, b) { return a.created_at < b.created_at ? -1 : 1; });
      state.oldest = drafts[0] || null;
      $('#oldest').text(state.oldest ? '가장 오래된 것 ' + shortTime(state.oldest.created_at) : '');
      $('#oldest-link').prop('hidden', !state.oldest);
      var owners = rows.map(function (r) { return r.action_owner; }).filter(function (v, i, a) { return v && a.indexOf(v) === i; }).sort();
      $('#owner-list').html(owners.map(function (o) { return '<option value="' + esc(o) + '">'; }).join(''));
      state.counts = c;
      listSummary();
    });
  }

  // 검토 지표 (로드맵 P2·P3)
  var kpi = {};
  function loadKb() {
    function put(id, pct, sub, side) {
      var $d = $('[data-kpi="' + id + '"]');
      if (pct == null) { $d.find('.v-cnt').text('–'); } else { countUp($d.find('.v-cnt'), pct, '%'); bar($d.find('.v-bar i'), pct); }
      $d.find('[data-r=sub]').text(sub || '');
      $d.find('[data-r=side]').text(side || '');
    }
    var p = function (x) { return x == null ? null : Math.round(x * 100); };
    var a = $.getJSON(API + '/knowledge').done(function (rows) {
      var total = 0, ai = 0;
      rows.forEach(function (r) { total += r.total; ai += r.ai_approved; });
      kpi.kb = total;
      $('#kb-pill').text('지식저장소 ' + total + '건').attr('title', 'AI 초안 승인 ' + ai + '건 포함. 다음 분석의 유사 사례로 쓰입니다.').prop('hidden', false);
    });
    var b = $.getJSON(API + '/review-stats').done(function (s) {
      kpi.review = s;
      if (!s.decided) { put('asis', null, '아직 결정된 AI 초안이 없습니다'); return; }
      put('asis', p(s.asIsRate), '결정된 AI 초안 ' + s.decided + '건 기준', '반려 ' + p(s.rejectionRate) + '%');
    });
    var c = $.getJSON(API + '/completeness').done(function (x) {
      kpi.full = x;
      if (!x.count) { put('full', null, 'AI 보고서가 아직 없습니다'); return; }
      put('full', p(x.completeRate), 'AI 보고서 ' + x.count + '건 · 11개 항목 기준');
    });
    $.when(a, b, c).always(listSummary);
  }

  function listSummary() {
    var c = state.counts;
    if (!c || !kpi.review || !kpi.full) { return; }
    var o = state.oldest, r = kpi.review, f = kpi.full, pct = function (x) { return Math.round(x * 100) + '%'; };
    var html = '검토 대기 ' + mark(c.DRAFT + '건') + '이 있' + (o ? '고, 가장 오래된 건은 ' + esc(shortTime(o.created_at)) + '에 올라온 ' +
      mark(o.title + '(' + o.ncr_no + ')') + '입니다.' : '습니다.');
    if (r.decided) { html += ' 결정된 AI 초안 ' + r.decided + '건 가운데 ' + mark(pct(r.asIsRate)) + '는 고치지 않고 그대로 승인됐고, ' + pct(r.rejectionRate) + '는 반려됐습니다.'; }
    if (f.count) { html += ' AI 보고서 ' + f.count + '건 가운데 필수 항목 11개를 모두 채운 비율은 ' + mark(pct(f.completeRate)) + '입니다.'; }
    if (o) { html += ' 오래된 건부터 확정 원인을 적고, 조치 담당자와 기한도 함께 지정하길 권합니다.'; }
    var sig = [c.DRAFT, c.APPROVED, c.CLOSED, c.REJECTED, o && o.ncr_no, r.decided, r.asIsRate, f.count, f.completeRate].join('|');
    summary('#sum-list', 'list', sig, html);
  }

  // ── 화면 전환: 주소의 #번호 가 있으면 상세, 없으면 목록 ──────────────
  function route() {
    var no = decodeURIComponent(location.hash.replace(/^#/, ''));
    if (no) {
      $('#view-list').prop('hidden', true);
      $('#view-detail').prop('hidden', false);
      if (no !== state.selected || !state.current) { loadDetail(no); }
      document.title = no + ' · 부적합 검토 · QualiGuard QMS';
    } else {
      $('#view-detail').prop('hidden', true);
      $('#view-list').prop('hidden', false);
      document.title = '부적합 검토 · QualiGuard QMS';
      var $sel = $('#rows tr[data-no="' + state.selected + '"]');
      if ($sel.length) { $sel.trigger('focus'); }
    }
  }
  // 주소에 남겨 두어 새로 고침·뒤로 가기·링크 공유가 된다. 화면은 바로 바꾼다
  function openDetail(no) {
    var h = '#' + encodeURIComponent(no);
    if (location.hash !== h) { history.pushState(null, '', h); }
    route();
  }
  function backToList() {
    history.pushState(null, '', location.pathname + location.search);
    route();
  }

  // ── 상세 ────────────────────────────────────────────────────
  var detailTabs = QG.tabs('.v-dtabs');

  function loadDetail(no) {
    state.selected = no;
    state.current = null;
    detailTabs.select('tab-review');
    $('#d-no').text(no);
    // 내용을 받기 전에는 입력·동작을 막는다: 먼저 적은 글이 늦게 온 응답(render)에 덮여 사라지지 않게
    $('#root-cause, #f-causeCode, [data-field], #assign-form input, #btn-save, #btn-approve, #btn-reject').prop('disabled', true);
    return $.getJSON(API + '/' + encodeURIComponent(no)).done(function (d) { render(d); $('#d-title').trigger('focus'); })
      .fail(function (xhr) { $('#d-title-text').text(no); msg(errorText(xhr), 'err'); });
  }

  // 처리 단계 흐름도: 초안 → 검토·승인 → MES/ERP 수신 → 조치 완료 (반려는 승인 자리에서 멈춘다)
  function drawProgress(d) {
    var acks = d.integrationStatus || [];
    var received = acks.some(function (s) { return s.status === 'RECEIVED' || s.status === 'PROCESSED'; });
    var failed = acks.length && acks.every(function (s) { return s.status === 'FAILED'; });
    var approved = d.status === 'APPROVED' || d.status === 'CLOSED';
    var closeAt = (d.history || []).filter(function (h) { return h.action === 'CLOSE'; })[0];
    var steps = [
      { label: '초안 작성', done: true, sub: '완료 · ' + shortTime(d.created_at) },
      d.status === 'REJECTED' ? { label: '반려', failed: true, sub: d.reviewed_at ? '반려 · ' + shortTime(d.reviewed_at) : '반려' }
        : { label: '검토·승인', done: approved, sub: approved ? '승인 · ' + (d.reviewed_at ? shortTime(d.reviewed_at) : '') :
          (d.root_cause ? '검토 대기' : '검토 대기 · 확정 원인 필요') },
      { label: 'MES/ERP 수신', done: approved && received && !failed, failed: approved && failed,
        sub: !approved ? '승인 뒤 진행' : (failed ? '실패 · 이력 탭에서 다시 보내기' : (received ? '수신 확인' : '응답 대기')) },
      { label: '조치 완료', done: d.status === 'CLOSED', sub: d.status === 'CLOSED' ? '완료' + (closeAt ? ' · ' + shortTime(closeAt.created_at) : '') : '대기' }
    ];
    var cur = -1;
    steps.forEach(function (s, i) { if (cur < 0 && !s.done && !s.failed) { cur = i; } });
    if (d.status === 'REJECTED') { cur = -1; }
    $('#d-progress').html(steps.map(function (s, i) {
      var now = i === cur;
      var st = s.failed ? '실패·중지' : (s.done ? '완료' : (now ? '현재 단계' : '남음'));
      return '<li class="' + (s.failed ? 'v-failed' : (s.done ? 'v-done' : (now ? 'v-now' : ''))) + '"' + (now ? ' aria-current="step"' : '') + '>' +
        '<span class="v-dot" aria-hidden="true">' + (s.done ? '✓' : (s.failed ? '!' : (i + 1))) + '</span>' +
        '<span class="v-lbl"><b>' + esc(s.label) + (now ? ' <em aria-hidden="true">현재 단계</em>' : '') + '</b><small>' + esc(s.sub) + '</small>' +
        '<span class="visually-hidden"> (' + st + ')</span></span></li>';
    }).join(''));
  }

  // 필드별 마지막 수정 (이력의 changes_json 에서)
  function lastEdits(d) {
    var out = {};
    (d.history || []).forEach(function (h) {
      if (h.action !== 'EDIT') { return; }
      var c = {};
      try { c = JSON.parse(h.changes_json || '{}'); } catch (e) { c = {}; }
      Object.keys(c).forEach(function (k) { if (!out[k] || out[k].created_at < h.created_at) { out[k] = h; } });
    });
    return out;
  }

  var ICON = {
    move: '<path d="M10 3v10M5.5 8.5L10 13l4.5-4.5"/><path d="M4 16.5h12"/>',
    edit: '<path d="M4 16l3.2-.8L16 6.4 13.6 4 4.8 12.8z"/><path d="M12 5.6l2.4 2.4"/>',
    trend: '<path d="M3 15l4-5 3 3 4-6 3 4"/>'
  };
  function sbtn(cls, icon, label, attrs) {
    return '<button type="button" class="v-sbtn ' + cls + '" ' + (attrs || '') + '>' +
      QG.svg(ICON[icon]) + '<span>' + esc(label) + '</span></button>';
  }
  function meta(items) {
    return '<dl class="v-aimeta">' + items.filter(function (m) { return m[1] != null && m[1] !== ''; }).map(function (m) {
      return '<div><dt>' + esc(m[0]) + '</dt><dd>' + (m[2] ? m[1] : esc(m[1])) + '</dd></div>';
    }).join('') + '</dl>';
  }
  function sensors(s) {
    return (s || '').split(',').map(function (x) { return $.trim(x); }).filter(Boolean)
      .map(function (x) { return '<code>' + esc(x) + '</code>'; }).join(', ');
  }

  // AI 제안: 줄마다 내용 + 메타(한 줄씩) + 오른쪽 아래 버튼. [수정]을 누르면 그 자리에서 고친다
  function drawAiRows(d, editable) {
    var edits = lastEdits(d), by = d.created_by === 'AGENT' ? 'AI 에이전트' : d.created_by;
    var shots = (d.anomalies || []).map(function (a) { return a.shot_no; }).filter(function (v) { return v != null; }).sort(function (a, b) { return a - b; });
    var rules = (d.anomalies || []).map(function (a) { return QG.label('rule', a.rule_code); }).filter(function (v, i, a) { return v && a.indexOf(v) === i; });
    var g = (d.analyses || [])[0];
    var rows = [
      { f: 'suspectedCause', label: '추정 원인', meta: [['작성', by], ['시각', QG.time(d.created_at)],
        ['확신도', d.confidence != null ? pctText(d.confidence) + ' (AI 자체 추정)' : ''], ['원인 코드', QG.label('cause', d.cause_code)]],
        acts: (editable ? sbtn('v-pri', 'move', '확정 원인으로 옮기기', 'data-move="1"') : '') },
      { f: 'phenomenon', label: '현상', meta: [['설비', d.equipment_id], ['센서', sensors(d.related_sensors), true],
        ['경보 shot', shots.length ? (shots.length > 1 ? shots[0] + '~' + shots[shots.length - 1] : String(shots[0])) : ''], ['발생', QG.time(d.occurred_at)]] },
      { f: 'evidence', label: '근거', meta: [['연결 경보', (d.anomalies || []).length + '건'], ['규칙', rules.join(', ')],
        ['분석', g ? '#' + g.analysis_id + (g.verify_status ? ' · ' + (g.verify_status === 'PASS' ? '검증 통과' : '근거 부족') : '') : '']],
        acts: sbtn('v-ghost', 'trend', '근거 탭에서 보기', 'data-go="tab-evidence"') },
      { f: 'recommendedAction', label: '권고 조치', meta: [['원본', by + ' · ' + QG.time(d.created_at)],
        ['수정', edits.recommendedAction ? edits.recommendedAction.actor + ' · ' + QG.time(edits.recommendedAction.created_at) : '없음'],
        ['담당자', d.action_owner || '지정 전'], ['조치 기한', d.action_due_date || '지정 전']] }
    ];
    $('#ai-rows').html(rows.map(function (r) {
      var v = d[COLUMN[r.f] || r.f] || '', e = edits[r.f];
      return '<div class="v-airow" data-row="' + r.f + '"><div class="v-ai-main">' +
        '<div class="v-flab"><label for="ai-' + r.f + '">' + esc(r.label) + '</label>' +
        (e ? ' <span class="v-edited">수정 · ' + esc(e.actor) + ' ' + esc(shortTime(e.created_at)) + '</span>' : '') + '</div>' +
        '<p class="v-aitext">' + (v ? esc(v) : '<span class="v-muted">비어 있음</span>') + '</p>' +
        '<textarea class="v-input v-aiedit" id="ai-' + r.f + '" data-field="' + r.f + '" rows="3" hidden></textarea>' +
        meta(r.meta) + '</div>' +
        '<div class="v-acts">' + (r.acts || '') + (editable ? sbtn('', 'edit', '수정', 'data-edit-row="' + r.f + '"') : '') + '</div></div>';
    }).join(''));
    rows.forEach(function (r) { $('#ai-' + r.f).val(d[COLUMN[r.f] || r.f] || ''); });
  }

  // 분류·조치 지정 표의 글자 칸: 값 + [수정] → 입력칸
  function drawEditRows(d, editable, assignable) {
    $('[data-edit]').each(function () {
      var $r = $(this), f = $r.attr('data-edit'), isAssign = !!$r.attr('data-assign');
      var v = isAssign ? (d[ASSIGN[f]] || '') : (d[COLUMN[f] || f] || '');
      var can = isAssign ? assignable : editable, mono = $r.attr('data-mono') ? ' qg-mono' : '';
      var missKey = f === 'affectedLots' ? '영향 범위' : $r.attr('data-label');
      $r.html('<label class="v-fl" for="e-' + f + '">' + esc($r.attr('data-label')) +
        (isAssign ? ' <span class="v-ai">자동</span>' : '') +
        ' <span class="v-miss-dot" data-miss="' + esc(missKey) + '" hidden>빠짐</span></label>' +
        '<div class="v-fv"><span class="v-val' + mono + '">' + (v ? esc(v) : '<span class="v-muted">비어 있음</span>') + '</span>' +
        '<input class="v-input' + mono + '" id="e-' + f + '" ' + (isAssign ? 'name="' + f + '"' : 'data-field="' + f + '"') + ' hidden>' +
        (can ? sbtn('v-sm', 'edit', '수정', 'data-edit-cell="' + f + '"') : '') + '</div>');
      $r.find('input').val(v);
    });
  }

  function render(d) {
    state.current = d;
    state.selected = d.ncr_no;
    var editable = d.status === 'DRAFT' && canWrite();
    var assignable = (d.status === 'DRAFT' || d.status === 'APPROVED') && canWrite();
    $('#d-no').text(d.ncr_no);
    $('#d-title-text').text(d.title);
    $('#d-status').html(statusTag(d.status));
    drawProgress(d);

    // 기본 정보 (한 줄씩)
    var c = d.completeness, g = (d.analyses || [])[0];
    var info = [['설비', esc(d.equipment_id)], ['발생', esc(QG.time(d.occurred_at))],
      ['작성', d.created_by === 'AGENT' ? '<span class="v-ai">AI 에이전트</span>' : esc(d.created_by)]];
    if (d.confidence != null) { info.push(['확신도', esc(pctText(d.confidence)) + ' <small>AI 자체 추정</small>']); }
    if (d.cause_code) { info.push(['원인 코드', esc(QG.label('cause', d.cause_code)) + ' <small class="qg-mono">' + esc(d.cause_code) + '</small>']); }
    if (g && g.verify_status) {
      info.push(['검증', (g.verify_status === 'PASS' ? '통과' : '근거 부족') + ' <small>' + (g.attempts || 1) + '회 · ' +
        ((g.latency_ms || 0) / 1000).toFixed(1) + '초</small>']);
    }
    if (c) { info.push(['필수 항목', c.passed + '/' + c.total + ' <small>' + esc(c.missing.length ? '빠짐: ' + c.missing.join(', ') : '모두 충족') + '</small>']); }
    if (d.reviewed_by) { info.push([(STATUS_LABEL[d.status] || '검토') + '자', esc(d.reviewed_by) + ' <small>' + esc(time(d.reviewed_at)) + '</small>']); }
    if (d.review_comment) { info.push(['의견', esc(d.review_comment)]); }
    $('#d-meta').html(info.map(function (m) { return '<div><dt>' + esc(m[0]) + '</dt><dd>' + m[1] + '</dd></div>'; }).join(''));
    $('#d-kb').prop('hidden', !(d.status === 'APPROVED' || d.status === 'CLOSED'));

    // ① 검토·확정
    var agent = d.created_by === 'AGENT';
    $('#p-review .v-sech h3').first().contents().first().replaceWith(agent ? 'AI 제안 ' : '작성 내용 ');
    $('#ai-state').text(agent && d.status === 'DRAFT' ? '검토 전' : '').prop('hidden', !(agent && d.status === 'DRAFT'));
    $('#ai-sub').text(agent ? (d.status === 'DRAFT' ? '에이전트 초안 · ' + QG.time(d.created_at) + ' · 승인 전까지 확정 내용이 아닙니다'
      : '에이전트 초안을 검토해 ' + (STATUS_LABEL[d.status] || '') + ' 처리한 내용입니다 · 읽기 전용') : '사람이 작성한 부적합');
    drawAiRows(d, editable);
    $('#root-cause').val(d.root_cause || '').prop('disabled', !editable).removeAttr('aria-invalid');

    // ② 분류·조치 지정
    $('#f-causeCode').val(d.cause_code || 'UNKNOWN').prop('disabled', !editable);
    drawEditRows(d, editable, assignable);
    $('#a-owner').val(d.action_owner || '');
    $('#a-due').val(d.action_due_date || '');
    $('#assign-form input, #owner-find, #due-pick').prop('disabled', !assignable);
    $('#btn-assign').prop('hidden', !assignable);
    var missing = c ? c.missing : [];
    $('[data-miss]').each(function () { $(this).prop('hidden', missing.indexOf($(this).attr('data-miss')) < 0); });
    var needRoot = d.status === 'DRAFT' && !d.root_cause;
    $('#b-review').text('필수 1').prop('hidden', !needRoot);
    var miss = missing.filter(function (m) { return ASSIGN_ITEMS.indexOf(m) >= 0; }).length;
    $('#b-assign').text('빠짐 ' + miss).prop('hidden', !miss);

    // 동작 바
    $('#btn-save, #btn-approve, #btn-reject').prop('disabled', !editable);
    $('#draft-actions').prop('hidden', !editable);
    $('#close-box').prop('hidden', d.status !== 'APPROVED' || !canWrite());
    $('#action-taken').prop('disabled', false).val('');
    if (!canWrite()) {
      msg('조회 전용 계정입니다. 수정·승인·반려는 품질 담당자 계정으로 할 수 있습니다.');
    } else {
      msg(editable ? '' : (d.status === 'APPROVED' ? '조치를 마치면 조치 내용을 적고 [조치 완료]를 누르세요.'
        : '초안(검토 대기) 상태에서만 수정·승인·반려할 수 있습니다.'));
    }

    // ③ 근거
    $('#a-count').text(d.anomalies.length + '건');
    $('#g-count').text(d.analyses.length + '건');
    $('#e-count').text(d.anomalies.length + d.analyses.length);
    $('#anomalies').html(d.anomalies.map(function (a) {
      return '<div class="v-ri"><span class="v-tile" aria-hidden="true">' + QG.svg('<path d="M10 3l7 13H3z"/><path d="M10 8v4"/>') + '</span>' +
        '<div><div class="v-t1">' + esc(QG.labelWithCode('rule', a.rule_code)) + '</div><div class="v-t2"><span class="qg-mono">' + esc(a.sensor_name) +
        '</span> · shot ' + esc(a.shot_no) + '</div><div class="v-t2">' + esc(a.message) + '</div></div></div>';
    }).join('') || '<div class="v-ri v-muted">연결된 경보 없음</div>');

    $('#analyses').html(d.analyses.map(function (g) {
      var r = {};
      try { r = JSON.parse(g.result_json || '{}'); } catch (e) { r = {}; }
      var head = '<strong class="qg-mono">#' + esc(g.analysis_id) + '</strong>' +
        QG.tone('neutral', g.trigger_type === 'RECHECK' ? '재확인' : '경보') +
        QG.tone('neutral', QG.label('decision', g.decision)) + verifyTag(g) +
        '<span class="muted">' + esc((g.cause_code ? QG.label('cause', g.cause_code) : '') + (g.as_of_shot != null ? ' · shot ' + g.as_of_shot : '') +
          ' · 확신도 ' + (g.confidence == null ? '-' : pctText(g.confidence)) + ' · ' + Math.round((g.latency_ms || 0) / 1000) + '초') + '</span>';
      return '<div class="analysis"><div class="analysis-head">' + head + '</div>' +
        (r.reasoningSummary ? '<p>' + esc(r.reasoningSummary) + '</p>' : '') +
        ((r.similarCases && r.similarCases.length) ? '<p class="muted">참고 사례: ' + esc(r.similarCases.join(', ')) + '</p>' : '') +
        verifyNotes(g) + pipelineNotes(g) +
        (g.tool_trace ? '<details class="qg-dev"><summary>개발자 정보: 도구 호출 순서</summary><pre>' + esc(g.tool_trace) + '</pre></details>' : '') +
        '</div>';
    }).join('') || '<p class="muted mb-0">분석 기록 없음 (사람이 작성한 부적합)</p>');

    // ④ 이력: MES/ERP 연계 + 검토 이력
    var ACK = { RECEIVED: '수신 확인', PROCESSED: '처리 완료', FAILED: '실패' };
    var latestFailed = false;
    var events = d.integration || [];
    $('#integration').html(events.map(function (o, i) {
      var st = o.published_at ? ('전송됨 ' + time(o.published_at)) : ('전송 대기' + (o.attempts ? ' (시도 ' + o.attempts + '회)' : ''));
      var line = '<strong>' + esc((o.event_type === 'NCR_APPROVED' ? '승인 이벤트' : o.event_type) + ' · ' + st) + '</strong>';
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
    $('#int-flag').html(latestFailed ? QG.tone('danger', '연계 실패') : '');
    drawFlow(d, events[events.length - 1]);

    $('#h-count').text(d.history.length);
    $('#history').html(d.history.map(function (h) {
      var changed = '';
      try {
        var c2 = JSON.parse(h.changes_json || '{}');
        changed = Object.keys(c2).map(function (k) { return FIELD_LABEL[k] || k; }).join(', ');
      } catch (e) { changed = ''; }
      return '<li><span class="v-hw">' + esc(time(h.created_at)) + '</span><div><div>' + esc(h.actor + ' · ' + (ACTION_LABEL[h.action] || h.action)) + '</div>' +
        ((h.comment || changed) ? '<div class="v-t2">' + esc([h.comment, changed ? '바꾼 항목: ' + changed : ''].filter(Boolean).join(' · ')) + '</div>' : '') +
        '</div></li>';
    }).join('') || '<li class="v-muted">아직 검토 이력이 없습니다.</li>');

    detailSummary(d, missing, g);
  }

  function detailSummary(d, missing, g) {
    var cause = QG.label('cause', d.cause_code);
    var html = esc(d.equipment_id) + ' ' + mark(d.title) + '. ';
    if (d.created_by === 'AGENT') {
      html += '에이전트가 ' + mark(cause) + '을(를) 원인으로 추정했습니다' + (d.confidence != null ? '(확신도 ' + esc(pctText(d.confidence)) + ', AI 자체 추정)' : '') + '. ';
    } else {
      html += esc(d.created_by) + '이(가) 작성했고 원인 코드는 ' + esc(cause) + '입니다. ';
    }
    html += '연결된 경보는 ' + d.anomalies.length + '건' + (g && g.verify_status ? '이고, 검증 에이전트는 ' + (g.attempts || 1) + '회 만에 ' +
      (g.verify_status === 'PASS' ? '통과' : '근거 부족') + '로 판단했습니다. ' : '입니다. ');
    if (d.status === 'DRAFT') {
      html += !d.root_cause ? '승인하려면 ' + mark('확정 원인') + '을 적어야 합니다. ' : '확정 원인이 적혀 있어 승인할 수 있습니다. ';
    } else {
      html += '지금 상태는 ' + esc(STATUS_LABEL[d.status] || d.status) + (d.reviewed_by ? '(' + esc(d.reviewed_by) + ')' : '') + '입니다. ';
    }
    var m = missing.filter(function (x) { return x === '담당자' || x === '조치 기한'; });
    if (m.length) { html += esc(m.join('와 ')) + '은(는) 아직 비어 있습니다.'; }
    var sig = [d.ncr_no, d.status, d.title, d.cause_code, d.confidence, !!d.root_cause, d.anomalies.length, g && g.verify_status,
      d.action_owner, d.action_due_date, d.history.length].join('|');
    summary('#sum-detail', 'ncr.' + d.ncr_no, sig, html);
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
    var pass = g.verify_status === 'PASS';
    var text = pass ? (retried ? '검증 통과 (재분석 ' + (g.attempts - 1) + '회)' : '검증 통과')
      : '근거 부족' + (retried ? ' (재분석 ' + (g.attempts - 1) + '회 후)' : '');
    return QG.tone(pass ? 'success' : 'danger', text);
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
    var head = '<div class="qg-pipe-head">' +
      QG.tone({ HIGH: 'danger', MEDIUM: 'warning' }[t.severity] || 'neutral',
        '분류 ' + (t.severity ? QG.label('severity', t.severity) : '-') + ' · ' + (t.type || '-')) +
      (p.report ? QG.tone('neutral', '초안 문장: ' + (REPORT_LABEL[p.report.mode] || p.report.mode) +
        ' · 클라우드 ' + (p.report.cloudCalls || 0) + '회') : '') + '</div>';
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
    $('[data-field]').each(function () { out[$(this).attr('data-field')] = $(this).val(); });
    return out;
  }

  // ── 내려받기 (엑셀·CSV): 화면에 받은 데이터로 만든다 ──
  var LIST_HEAD = ['번호', '제목', '상태', '설비', '원인', '확신도', '작성', '작성 시각'];
  function listExport(format) {
    var picked = visible.filter(function (r) { return state.checked[r.ncr_no]; });
    var rows = (picked.length ? picked : visible).map(function (r) {
      return [r.ncr_no, r.title, STATUS_LABEL[r.status] || r.status, r.equipment_id, QG.label('cause', r.cause_code),
        pctText(r.confidence), r.created_by === 'AGENT' ? 'AI 에이전트' : r.created_by, time(r.created_at)];
    });
    QGExport.download('ncr-list', '부적합 목록', format, LIST_HEAD, rows);
    toast('부적합 목록 ' + rows.length + '건을 ' + (format === 'csv' ? 'CSV' : '엑셀') + ' 파일로 내려받았습니다' + (picked.length ? ' (선택한 건)' : ''));
  }
  function detailExport(format) {
    var d = state.current;
    if (!d) { return; }
    var rows = [['번호', d.ncr_no], ['제목', d.title], ['상태', STATUS_LABEL[d.status] || d.status], ['설비', d.equipment_id],
      ['발생', time(d.occurred_at)], ['작성', d.created_by === 'AGENT' ? 'AI 에이전트' : d.created_by], ['작성 시각', time(d.created_at)],
      ['확신도', pctText(d.confidence)], ['원인 코드', QG.labelWithCode('cause', d.cause_code)], ['불량 유형', d.defect_type],
      ['관련 센서', d.related_sensors], ['현상', d.phenomenon], ['추정 원인', d.suspected_cause], ['확정 원인', d.root_cause],
      ['근거', d.evidence], ['권고 조치', d.recommended_action], ['담당자', d.action_owner], ['조치 기한', d.action_due_date],
      ['영향 로트', d.affected_lots], ['검토자', d.reviewed_by], ['검토 시각', time(d.reviewed_at)], ['의견', d.review_comment]];
    d.anomalies.forEach(function (a, i) {
      rows.push(['연결 경보 ' + (i + 1), 'shot ' + a.shot_no + ' · ' + QG.label('rule', a.rule_code) + ' · ' + a.sensor_name + ' · ' + a.message]);
    });
    QGExport.download(d.ncr_no.replace(/[^\w-]/g, '_'), d.ncr_no, format, ['항목', '내용'], rows.map(function (r) { return [r[0], r[1] == null ? '' : r[1]]; }));
    toast(d.ncr_no + ' 을(를) ' + (format === 'csv' ? 'CSV' : '엑셀') + ' 파일로 내려받았습니다');
  }
  $(document).on('click', '[data-export]', function () {
    var f = $(this).attr('data-export');
    if ($(this).attr('data-scope') === 'detail') { detailExport(f); } else { listExport(f); }
  });

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
  $('#btn-save').on('click', save);

  // AI 제안 줄: [수정] → 그 자리 입력칸, [확정 원인으로 옮기기] → 확정 원인 칸에 붙인다
  $('#ai-rows').on('click', '[data-edit-row]', function () {
    var f = $(this).attr('data-edit-row'), $row = $(this).closest('.v-airow');
    $row.find('.v-aitext').prop('hidden', true);
    $row.find('textarea').prop('hidden', false).trigger('focus');
    $(this).prop('hidden', true);
    msg(FIELD_LABEL[f] + ' 을(를) 고친 뒤 [저장]을 누르세요.');
  });
  $('#ai-rows').on('click', '[data-move]', function () {
    var v = $.trim($('#ai-suspectedCause').val());
    var $r = $('#root-cause');
    $r.val($.trim($r.val()) ? $.trim($r.val()) + '\n' + v : v).removeAttr('aria-invalid').trigger('focus');
    msg('추정 원인을 확정 원인 칸에 옮겼습니다. 현장에서 확인한 내용으로 고친 뒤 승인하세요.');
  });
  $(document).on('click', '[data-go]', function () { detailTabs.select($(this).attr('data-go')); $('#' + $(this).attr('data-go')).trigger('focus'); });
  $('#p-assign').on('click', '[data-edit-cell]', function () {
    var $fv = $(this).closest('.v-fv');
    $fv.find('.v-val').prop('hidden', true);
    $fv.find('input').prop('hidden', false).trigger('focus');
    $(this).prop('hidden', true);
  });
  $('#owner-find').on('click', function () {
    var el = document.getElementById('a-owner');
    el.focus();
    try { if (el.showPicker) { el.showPicker(); } } catch (e) { /* 후보 목록은 입력하면 보인다 */ }
  });
  $('#due-pick').on('click', function () {
    var el = document.getElementById('a-due');
    el.focus();
    try { if (el.showPicker) { el.showPicker(); } } catch (e) { /* 지원하지 않는 브라우저는 입력칸에서 고른다 */ }
  });

  // 조치 담당자·기한·영향 로트 저장 (로드맵 P2). 빈 칸으로 저장하면 그 값을 지운다.
  $('#assign-form').on('submit', function (e) {
    e.preventDefault();
    if (!requireActor()) { return; }
    var body = {};
    Object.keys(ASSIGN).forEach(function (k) { body[k] = $('#assign-form [name="' + k + '"]').val(); });
    // 조치 지정 저장은 담당자·기한·영향 로트만 바꾼다. 다시 그릴 때 아직 저장하지 않은 확정 원인 등 수정을 지우지 않게 되살린다
    var before = editedFields();
    send('PUT', API + '/' + encodeURIComponent(state.selected) + '/assignment', body)
      .done(function (d) {
        render(d);
        var server = editedFields(), kept = 0;
        Object.keys(before).forEach(function (k) {
          if (before[k] === server[k]) { return; }
          var $el = $('[data-field="' + k + '"]').val(before[k]);
          if ($el.prop('hidden')) { $el.prop('hidden', false).siblings('.v-val, .v-aitext').prop('hidden', true); }
          kept += 1;
        });
        detailTabs.select('tab-assign');
        msg('조치 지정을 저장했습니다.' + (kept ? ' 저장하지 않은 다른 수정 ' + kept + '건은 그대로 남아 있습니다. [저장]을 눌러 주세요.' : ''), 'ok');
        loadCounts();
      })
      .fail(function (xhr) { msg(errorText(xhr), 'err'); });
  });

  $('#btn-approve').on('click', function () {
    var a = requireActor();
    if (!a) { return; }
    var no = state.selected;
    if (!$.trim($('#root-cause').val())) {
      msg('확정 원인을 입력해야 승인할 수 있습니다.', 'err');
      detailTabs.select('tab-review');
      $('#root-cause').attr('aria-invalid', 'true').trigger('focus');
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
      .done(function (d) { render(d); detailTabs.select('tab-hist'); msg('MES/ERP 로 다시 보냈습니다. 응답을 기다리는 중입니다.', 'ok'); })
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

  // ── 목록 조작 ──
  $('#f-status').on('change', function () { state.status = $(this).val(); state.page = 1; loadList(); });
  $('#f-cause, #f-equip, #f-key').on('change', function () { state.page = 1; drawRows(); });
  $('#search-form').on('submit', function (e) { e.preventDefault(); state.query = $.trim($('#search').val() || ''); state.page = 1; drawRows(); });
  $('#search').on('input', function () { if (!$(this).val() && state.query) { state.query = ''; drawRows(); } });
  $('#page-size').on('change', function () { state.size = Number($(this).val()) || 10; state.page = 1; drawRows(); });
  $('#pager').on('click', 'button[data-p]', function () { state.page = Number($(this).attr('data-p')); drawRows(); });
  $('#rows').on('click', 'td.v-ck', function (e) { e.stopPropagation(); });
  $('#rows').on('change', '.v-rowck', function () {
    var no = String($(this).closest('tr').data('no'));
    if (this.checked) { state.checked[no] = true; } else { delete state.checked[no]; }
    $(this).closest('tr').toggleClass('v-sel', this.checked);
    syncChecks();
  });
  $('#ck-all').on('change', function () {
    var on = this.checked;
    $('#rows .v-rowck').each(function () { this.checked = on; $(this).trigger('change'); });
  });
  $('#oldest-link').on('click', function (e) { e.preventDefault(); if (state.oldest) { openDetail(state.oldest.ncr_no); } });

  // 목록 표: 행을 누르거나 Enter 로 상세를 연다. 열 제목을 누르면 정렬한다
  $('#rows').on('click', 'tr.pick', function (e) {
    e.preventDefault();
    openDetail(String($(this).data('no')));
  });
  $('#rows').on('keydown', 'tr.pick', function (e) {
    if (e.key === 'Enter' && e.target === this) { openDetail(String($(this).data('no'))); }
  });
  $('.qg-table-list thead').on('click', 'th[data-sort]', function () {
    var k = $(this).attr('data-sort');
    sort = { key: k, dir: sort.key === k ? -sort.dir : (k === 'created_at' || k === 'confidence' ? -1 : 1) };
    drawRows();
  });
  $('.qg-table-list th[data-sort]').attr('tabindex', '0').on('keydown', function (e) {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $(this).trigger('click'); }
  });

  $('#back-to-list').on('click', function (e) { e.preventDefault(); backToList(); });
  $('#root-cause').on('input', function () { $(this).removeAttr('aria-invalid'); });
  $(window).on('hashchange popstate', route);

  QG.me().done(function (me) {
    state.me = me;
    loadList().always(route);
    loadKb();
  });
}(jQuery));
