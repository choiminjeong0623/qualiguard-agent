/* QMS 공통 스크립트 (UI 리디자인 2단계) — 모든 화면이 body 끝, 화면 스크립트 바로 앞에 싣는다 (셸 자리가 먼저 있어야 함).
   - 요청 공통 처리: 쓰기 요청에 CSRF 헤더, 401 이면 로그인 화면으로
   - 공통 셸: 머리 띠(이름·주 메뉴·사용자 + 화면 제목)를 한 곳에서 그린다 (v3 테마)
   - 표기: 코드의 한글 이름, 상태 표시(모양+문구+색), 시각 형식
   - 위험 동작 확인 대화상자 (<dialog>)
   화면 스크립트는 window.QG 로 쓴다. */
(function ($) {
  'use strict';

  // ── 요청 ─────────────────────────────────────────────────────
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

  function esc(s) { return $('<div>').text(s == null ? '' : String(s)).html(); }
  function errorText(xhr) {
    var b = xhr && xhr.responseJSON;
    return (b && (b.error || b.message)) ? (b.error || b.message) : ('요청 실패 (' + (xhr ? xhr.status : '?') + ')');
  }

  // ── 시각: 시간대가 붙은 값은 이 PC 시간대로 바꿔 보이고, 없는 값은 받은 그대로 보인다 ──
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function time(s, withSeconds) {
    if (!s) { return ''; }
    var str = String(s);
    if (/(Z|[+-]\d{2}:?\d{2})$/.test(str)) {
      var d = new Date(str);
      if (!isNaN(d.getTime())) {
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' +
          pad(d.getMinutes()) + (withSeconds ? ':' + pad(d.getSeconds()) : '');
      }
    }
    var t = str.replace('T', ' ').replace(/\.\d+/, '').replace(/(Z|[+-]\d{2}:?\d{2})$/, '');
    return withSeconds ? t.substring(0, 19) : t.substring(0, 16);
  }

  // ── 코드의 한글 이름 (코드는 괄호로 함께 보인다) ──────────────────
  var LABELS = {
    cause: { HEATER_DEGRADATION: '히터 열화', COOLING_ISSUE: '냉각 이상', CHECK_RING_WEAR: '체크 링 마모',
      SENSOR_FAULT: '센서 이상', SENSOR_CHECK: '센서 점검', MATERIAL: '원재료', NOISE: '단발성 노이즈', UNKNOWN: '원인 미상' },
    decision: { OBSERVE: '관찰', SENSOR_CHECK: '센서 점검', NCR_DRAFT: '부적합 초안', NOISE: '노이즈' },
    rule: { XBAR: '평균 이탈 (X̄)', S_CHART: '산포 이탈 (S)', P_CHART: '불량률 이탈 (p)', INDIVIDUAL: '개별값 이탈' },
    risk: { LOW: '저위험', MEDIUM: '중위험', HIGH: '고위험' },
    severity: { LOW: '낮음', MEDIUM: '중간', HIGH: '높음' }
  };
  function label(kind, code) { return (LABELS[kind] && LABELS[kind][code]) || code || ''; }
  function labelWithCode(kind, code) {
    var l = label(kind, code);
    return code && l !== code ? l + ' (' + code + ')' : (code || '');
  }

  // ── 상태 표시: 업무 상태 → 의미 5종 (계획서 6.4) ──────────────────
  var STATES = {
    ncr: { DRAFT: ['warning', '검토 대기'], APPROVED: ['info', '승인'], CLOSED: ['success', '조치 완료'], REJECTED: ['stopped', '반려'] },
    action: { WAITING_APPROVAL: ['warning', '승인 대기'], EXECUTED: ['info', '효과 확인 중'], RESOLVED: ['success', '해소'],
      NOT_RESOLVED: ['danger', '효과 없음 · 재분석'], ESCALATED: ['danger', '사람 확인 필요'], REJECTED: ['stopped', '반려'] },
    risk: { LOW: ['success', '저위험'], MEDIUM: ['warning', '중위험'], HIGH: ['danger', '고위험'] },
    inspection: { OK: ['success', 'OK'], NG: ['danger', 'NG'] },
    verify: { PASS: ['success', '검증 통과'], WEAK: ['danger', '근거 부족'], FAIL: ['danger', '근거 부족'] },
    ack: { PROCESSED: ['success', '처리 완료'], RECEIVED: ['info', '수신 확인'], FAILED: ['danger', '실패'] }
  };
  function stateTag(kind, code, text) {
    var s = (STATES[kind] && STATES[kind][code]) || ['neutral', code || '-'];
    return '<span class="qg-state" data-tone="' + s[0] + '">' + esc(text || s[1]) + '</span>';
  }
  function tone(tone, text) { return '<span class="qg-state" data-tone="' + esc(tone) + '">' + esc(text) + '</span>'; }

  // ── 확인 대화상자: 위험 동작 전에. 확인하면 resolve(입력값), 취소하면 reject ──
  // opts: { title, body, ok, danger, input: { label, placeholder, required, maxlength, error } }
  function confirmDialog(opts) {
    var d = $.Deferred();
    var dlg = document.createElement('dialog');
    dlg.className = 'qg-dialog';
    dlg.setAttribute('aria-labelledby', 'qg-dialog-title');
    var inputId = 'qg-dialog-input';
    dlg.innerHTML = '<form method="dialog" class="qg-dialog-form" novalidate>' +
      '<h2 class="qg-dialog-title" id="qg-dialog-title">' + esc(opts.title) + '</h2>' +
      '<div class="qg-dialog-body">' + (opts.bodyHtml || ('<p>' + esc(opts.body || '') + '</p>')) +
      (opts.input ? '<label class="form-label" for="' + inputId + '">' + esc(opts.input.label || '') + '</label>' +
        '<textarea id="' + inputId + '" class="form-control" rows="3" maxlength="' + (opts.input.maxlength || 1000) +
        '" placeholder="' + esc(opts.input.placeholder || '') + '"></textarea><p class="qg-field-error" role="alert"></p>' : '') +
      '</div><div class="qg-dialog-actions">' +
      '<button type="button" class="btn" value="cancel">취소</button>' +
      '<button type="submit" class="btn ' + (opts.danger ? 'btn-danger' : 'btn-primary') + '" value="ok">' + esc(opts.ok || '확인') + '</button>' +
      '</div></form>';
    document.body.appendChild(dlg);
    var $dlg = $(dlg);
    var result = null;
    $dlg.find('button[value=cancel]').on('click', function () { dlg.close(); });
    $dlg.find('form').on('submit', function (e) {
      e.preventDefault();
      if (opts.input) {
        var v = $.trim($dlg.find('#' + inputId).val());
        if (opts.input.required && !v) {
          $dlg.find('.qg-field-error').text(opts.input.error || '내용을 입력하세요.');
          $dlg.find('#' + inputId).attr('aria-invalid', 'true').trigger('focus');
          return;
        }
        result = { value: v };
      } else {
        result = { value: true };
      }
      dlg.close();
    });
    dlg.addEventListener('close', function () {
      if (result) { d.resolve(result.value); } else { d.reject(); }
      setTimeout(function () { dlg.remove(); }, 0);
    });
    dlg.showModal();
    $dlg.find(opts.input ? 'textarea' : 'button[value=ok]').trigger('focus');
    return d.promise();
  }

  // ── 탭 (WAI-ARIA Tabs): 누르거나 화살표·Home·End 로 옮기면 그 탭의 패널만 보인다 ──
  // 마크업: [role=tablist] 안의 [role=tab][aria-controls] + [role=tabpanel]. onChange(탭 id) 는 선택이 바뀔 때.
  function tabs(list, onChange) {
    var $list = $(list);
    function pick($t, focus) {
      var $all = $list.find('[role=tab]');
      $all.attr({ 'aria-selected': 'false', tabindex: '-1' }).each(function () {
        $('#' + $(this).attr('aria-controls')).prop('hidden', true);
      });
      $t.attr({ 'aria-selected': 'true', tabindex: '0' });
      $('#' + $t.attr('aria-controls')).prop('hidden', false);
      if (focus) { $t.trigger('focus'); }
      if (onChange) { onChange($t.attr('id')); }
    }
    $list.on('click', '[role=tab]', function () { pick($(this), false); });
    $list.on('keydown', '[role=tab]', function (e) {
      var $all = $list.find('[role=tab]'), i = $all.index(this), n = $all.length, j = null;
      if (e.key === 'ArrowRight') { j = (i + 1) % n; } else if (e.key === 'ArrowLeft') { j = (i - 1 + n) % n; }
      else if (e.key === 'Home') { j = 0; } else if (e.key === 'End') { j = n - 1; }
      if (j === null) { return; }
      e.preventDefault();
      pick($all.eq(j), true);
    });
    $list.find('[role=tab][aria-selected!="true"]').attr('tabindex', '-1');
    return { select: function (id) { pick($list.find('#' + id), false); } };
  }

  // ── 공통 셸 (v3 테마): 짙은 그라데이션 머리 띠 = 윗줄(이름·주 메뉴·사용자) + 화면 제목 ──
  // 메뉴는 저장소에 화면이 있는 것만 둔다 (계획서 6.2, 결정 D6). 사이드 메뉴는 v3 에서 없앴다.
  var ICON = {
    ncr: '<rect x="4" y="3" width="12" height="15" rx="1.5"/><path d="M7.5 3V2h5v1M7.5 11l2 2 3.5-4"/>',
    shipment: '<path d="M3 5v10M6 5v10M9 5v10M12 5v10M15 5v10M17.5 5v10"/>',
    actions: '<circle cx="10" cy="10" r="7"/><path d="M8 7.5v5M12 7.5v5"/>'
  };
  var MENU = [
    { id: 'ncr', href: 'index.html', label: '부적합 검토' },
    { id: 'shipment', href: 'shipment.html', label: '출하 시리얼' },
    { id: 'actions', href: 'actions.html', label: '조치·판단 보류' }
  ];
  function svg(path, cls) {
    return '<svg class="' + (cls || '') + '" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + path + '</svg>';
  }

  // ── 글자 크기·선명하게 보기 (KRDS 디지털 포용: 글자 크기 5단계, 선명한 화면) ──
  // 고른 값은 이 브라우저에만 남긴다(localStorage). 저장이 막혀도 화면은 기본값으로 그대로 쓴다
  var SIZES = [['0.9', '작게'], ['1', '보통'], ['1.1', '조금 크게'], ['1.3', '크게'], ['1.5', '가장 크게']];
  function readPref(k, d) { try { return localStorage.getItem(k) || d; } catch (e) { return d; } }
  function savePref(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* 저장 못 해도 이번 화면에는 적용됨 */ } }
  function applyA11y() {
    var z = readPref('qg-zoom', '1'), hc = readPref('qg-contrast', '');
    document.documentElement.style.zoom = z === '1' ? '' : z;
    if (hc === 'high') { document.documentElement.setAttribute('data-qg-contrast', 'high'); } else { document.documentElement.removeAttribute('data-qg-contrast'); }
  }
  applyA11y();
  function a11yControl() {
    var z = readPref('qg-zoom', '1');
    var sizes = SIZES.map(function (s) {
      return '<label><input type="radio" name="qg-zoom" value="' + s[0] + '"' + (s[0] === z ? ' checked' : '') + '><span>' + s[1] + '</span></label>';
    }).join('');
    return '<span class="v-a11y"><button type="button" class="v-iconbtn v-a11y-btn" id="qg-a11y-toggle" aria-expanded="false" aria-controls="qg-a11y-pop">' +
      '<b aria-hidden="true">가</b>글자·화면</button>' +
      '<div class="v-a11y-pop" id="qg-a11y-pop" role="dialog" aria-label="글자 크기와 화면 설정" hidden>' +
      '<fieldset><legend>글자 크기</legend><div class="v-a11y-sizes">' + sizes + '</div></fieldset>' +
      '<label class="v-a11y-row"><input type="checkbox" id="qg-contrast"' + (readPref('qg-contrast', '') === 'high' ? ' checked' : '') + '>선명하게 보기(고대비)</label>' +
      '<button type="button" class="v-btn v-a11y-reset" id="qg-a11y-reset">기본값으로</button></div></span>';
  }
  function wireA11y() {
    var $btn = $('#qg-a11y-toggle'), $pop = $('#qg-a11y-pop');
    function open(on) { $pop.prop('hidden', !on); $btn.attr('aria-expanded', String(on)); $btn.closest('.v-hero').toggleClass('v-a11y-open', on); if (on) { $pop.find('input:checked').first().trigger('focus'); } }
    $btn.on('click', function (e) { e.stopPropagation(); open($pop.prop('hidden')); });
    $pop.on('click', function (e) { e.stopPropagation(); });
    $(document).on('click', function () { if (!$pop.prop('hidden')) { open(false); } });
    $(document).on('keydown', function (e) { if (e.key === 'Escape' && !$pop.prop('hidden')) { open(false); $btn.trigger('focus'); } });
    $pop.on('change', 'input[name=qg-zoom]', function () { savePref('qg-zoom', this.value); applyA11y(); });
    $('#qg-contrast').on('change', function () { savePref('qg-contrast', this.checked ? 'high' : ''); applyA11y(); });
    $('#qg-a11y-reset').on('click', function () {
      savePref('qg-zoom', '1'); savePref('qg-contrast', ''); applyA11y();
      $pop.find('input[value="1"]').prop('checked', true); $('#qg-contrast').prop('checked', false);
    });
  }

  // ── 표 칸 이름: 좁은 화면에서 행을 카드로 흘릴 때 칸마다 열 제목을 붙인다 (qg-mobile.css) ──
  function labelCells(table) {
    var names = $(table).find('thead th').map(function () { return $(this).hasClass('v-ck') ? '선택' : $.trim($(this).text()); }).get();
    $(table).find('tbody tr').each(function () {
      var i = 0;
      $(this).children('td').each(function () {
        var span = parseInt(this.getAttribute('colspan') || '1', 10);
        if (span === 1 && names[i] != null && !this.hasAttribute('data-label')) { this.setAttribute('data-label', names[i]); }
        i += span;
      });
    });
  }
  function watchTables() {
    $('table').each(function () {
      var table = this, tbody = $(table).find('tbody')[0];
      if (!$(table).find('thead th').length || !tbody) { return; }
      labelCells(table);
      if (window.MutationObserver) { new MutationObserver(function () { labelCells(table); }).observe(tbody, { childList: true }); }
    });
  }

  function renderShell() {
    var $header = $('header.qg-shell[data-active]');
    if (!$header.length) { return; }
    var active = $header.attr('data-active');
    // 오른쪽 장식: 옅은 격자 위에서 칸 몇 개가 차례로 켜졌다 꺼진다 (동작 줄이기 설정이면 멈춤)
    var cells = [[15, 15], [43, 29], [85, 15], [29, 57], [127, 43], [71, 71], [169, 29], [113, 85]].map(function (c, i) {
      return '<span class="v-fx-cell" style="right:' + c[0] + 'px;top:' + c[1] + 'px;animation-delay:' + (i * 1.1).toFixed(1) + 's"></span>';
    }).join('');
    var nav = MENU.map(function (it) {
      var cur = it.id === active;
      return '<a href="' + it.href + '"' + (cur ? ' aria-current="page" class="v-on"' : '') + '>' + svg(ICON[it.id], 'v-ni') +
        '<span>' + esc(it.label) + '</span><span class="v-n" data-badge="' + it.id + '" hidden></span></a>';
    }).join('');
    $header.addClass('v-hero').attr('data-fx', 'c').html(
      '<div class="v-hero-fx" aria-hidden="true"><span class="v-fx-grid"></span>' + cells + '</div>' +
      '<div class="v-topbar">' +
      '<button type="button" class="v-iconbtn v-menu-btn" id="qg-menu-toggle" aria-controls="qg-nav" aria-expanded="false" title="메뉴">' +
      svg('<path d="M4 6h12M4 10h12M4 14h12"/>') + '<span class="visually-hidden">메뉴</span></button>' +
      '<a class="v-logo" href="index.html">QualiGuard</a><span class="v-vsep" aria-hidden="true"></span>' +
      '<nav class="v-nav" id="qg-nav" aria-label="주 메뉴">' + nav + '</nav>' +
      '<span class="v-sp"></span>' +
      '<span id="kb" class="qg-shell-item" hidden></span>' +
      a11yControl() +
      '<span class="v-me"><span id="me" aria-live="polite"></span><span class="v-avatar" id="me-avatar" aria-hidden="true"></span></span>' +
      '<button type="button" id="btn-logout" class="v-iconbtn" title="로그아웃">' + svg('<path d="M8 4H4v12h4M12 6l4 4-4 4M16 10H8"/>') +
      '<span class="visually-hidden">로그아웃</span></button></div>');
    // 화면 제목 줄(data-hero)은 머리 띠 안으로 옮긴다
    var $title = $('[data-hero]').first();
    if ($title.length) { $title.addClass('v-hero-body').appendTo($header); }

    // 좁은 화면: 메뉴 단추로 주 메뉴를 펼친다. Esc 나 바깥을 누르면 닫는다
    function openNav(open) {
      $header.toggleClass('v-nav-open', open);
      $('#qg-menu-toggle').attr('aria-expanded', String(open));
    }
    $('#qg-menu-toggle').on('click', function (e) { e.stopPropagation(); openNav(!$header.hasClass('v-nav-open')); });
    $(document).on('keydown', function (e) { if (e.key === 'Escape' && $header.hasClass('v-nav-open')) { openNav(false); } });
    $(document).on('click', function (e) { if (!$(e.target).closest('#qg-nav').length) { openNav(false); } });

    $('#btn-logout').on('click', function () {
      $.ajax({ method: 'POST', url: '/logout' }).always(function () { location.href = 'login.html?logout'; });
    });
    wireA11y();
    loadBadges();
  }

  // 메뉴 배지: 사람이 처리할 건수 (부적합 검토 대기, 승인 대기 조치 + 판단 보류)
  function badge(id, n) {
    $('[data-badge="' + id + '"]').text(n).attr('aria-label', '처리할 일 ' + n + '건').prop('hidden', !n);
  }
  function loadBadges() {
    $.getJSON('/api/qms/ncrs', { status: 'DRAFT', top: 500 }).done(function (rows) { badge('ncr', rows.length); });
    $.when($.getJSON('/api/qms/actions', { top: 100 }), $.getJSON('/api/qms/holds', { status: 'PENDING', top: 50 }))
      .done(function (a, h) {
        var waiting = (a[0] || []).filter(function (x) { return x.status === 'WAITING_APPROVAL'; }).length;
        badge('actions', waiting + (h[0] || []).length);
      });
  }

  // ── 로그인 사용자: 한 번만 읽어 셸에 표시하고 화면 스크립트에 넘긴다 ──
  var meReq = null;
  function me() {
    if (!meReq) {
      meReq = $.getJSON('/api/qms/me').done(function (u) {
        var role = u.role === 'REVIEWER' ? '품질 담당자' : '조회 전용';
        $('#me').html(u.displayName === role ? esc(role) : '<b>' + esc(u.displayName) + '</b> · ' + esc(role));
        $('#me-avatar').text((u.displayName || role).charAt(0));
      });
    }
    return meReq;
  }

  window.QG = { csrf: csrf, esc: esc, errorText: errorText, time: time, label: label, labelWithCode: labelWithCode,
    stateTag: stateTag, tone: tone, confirm: confirmDialog, tabs: tabs, me: me, LABELS: LABELS, svg: svg, badge: badge };

  renderShell();
  watchTables();

  // 가로로 넘칠 수 있는 표 상자는 키보드로도 스크롤할 수 있게 (WCAG 2.1.1, axe scrollable-region-focusable)
  $('.table-responsive').each(function () {
    var $cap = $(this).find('caption').first();
    $(this).attr({ tabindex: '0', role: 'region' });
    if ($cap.attr('id')) { $(this).attr('aria-labelledby', $cap.attr('id')); } else { $(this).attr('aria-label', $cap.text() || '표'); }
  });
}(jQuery));
