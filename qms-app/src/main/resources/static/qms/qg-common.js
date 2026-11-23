/* QMS 공통 스크립트 (UI 리디자인 2단계) — 모든 화면이 body 끝, 화면 스크립트 바로 앞에 싣는다 (셸 자리가 먼저 있어야 함).
   - 요청 공통 처리: 쓰기 요청에 CSRF 헤더, 401 이면 로그인 화면으로
   - 공통 셸: 상단 바 + 사이드 메뉴를 한 곳에서 그린다 (화면마다 다르던 헤더 정리)
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

  // ── 공통 셸: 상단 바 + 사이드 메뉴 ─────────────────────────────
  // 메뉴는 저장소에 화면이 있는 것만 둔다 (계획서 6.2, 결정 D6)
  var MENU = [
    { group: '품질 관리', items: [
      { id: 'ncr', href: 'index.html', label: '부적합 검토' },
      { id: 'shipment', href: 'shipment.html', label: '출하 시리얼' }] },
    { group: 'AI 에이전트', items: [
      { id: 'actions', href: 'actions.html', label: '조치·판단 보류' }] }
  ];
  var SIDE_KEY = 'qg.sidebar.collapsed';
  function stored(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }
  function store(key, v) { try { localStorage.setItem(key, v); } catch (e) { /* 저장 못 해도 화면은 동작 */ } }

  function renderShell() {
    var $header = $('header.qg-shell[data-active]');
    if (!$header.length) { return; }
    var active = $header.attr('data-active');
    $header.html(
      '<button type="button" class="qg-shell-icon" id="qg-menu-toggle" aria-controls="qg-side" aria-expanded="true" title="메뉴 접기·펼치기">' +
      '<span class="qg-burger" aria-hidden="true"></span><span class="visually-hidden">메뉴</span></button>' +
      '<a class="qg-brand" href="index.html"><span class="qg-logo" aria-hidden="true">QG</span>QualiGuard <span class="qg-brand-sub">QMS</span></a>' +
      '<span class="qg-shell-spacer"></span>' +
      '<span id="kb" class="qg-shell-item" hidden></span>' +
      '<span class="qg-shell-item" id="me" aria-live="polite"></span>' +
      '<button type="button" id="btn-logout" class="qg-shell-btn">로그아웃</button>');
    var nav = MENU.map(function (g) {
      return '<div class="qg-side-group"><div class="qg-side-title">' + esc(g.group) + '</div><ul>' +
        g.items.map(function (it) {
          var cur = it.id === active;
          return '<li><a href="' + it.href + '"' + (cur ? ' aria-current="page" class="active"' : '') +
            ' title="' + esc(it.label) + '"><span class="qg-side-mark" aria-hidden="true">' + esc(it.label.charAt(0)) +
            '</span><span class="qg-side-label">' + esc(it.label) + '</span></a></li>';
        }).join('') + '</ul></div>';
    }).join('');
    $('#qg-side').attr('aria-label', '주 메뉴').html(nav);

    var narrow = window.matchMedia('(max-width: 1023px)');
    function apply(collapsed) {
      $('body').toggleClass('qg-side-collapsed', collapsed);
      $('#qg-menu-toggle').attr('aria-expanded', String(!collapsed));
    }
    apply(narrow.matches ? true : stored(SIDE_KEY) === '1');
    $('#qg-menu-toggle').on('click', function () {
      var collapsed = !$('body').hasClass('qg-side-collapsed');
      apply(collapsed);
      if (!narrow.matches) { store(SIDE_KEY, collapsed ? '1' : '0'); }
    });
    // 좁은 화면: 메뉴는 화면 위에 겹쳐 열리고, 바깥을 누르거나 Esc 로 닫는다
    $(document).on('keydown', function (e) {
      if (e.key === 'Escape' && narrow.matches && !$('body').hasClass('qg-side-collapsed')) { apply(true); }
    });
    $('#qg-side-scrim').on('click', function () { apply(true); });

    $('#btn-logout').on('click', function () {
      $.ajax({ method: 'POST', url: '/logout' }).always(function () { location.href = 'login.html?logout'; });
    });
  }

  // ── 로그인 사용자: 한 번만 읽어 셸에 표시하고 화면 스크립트에 넘긴다 ──
  var meReq = null;
  function me() {
    if (!meReq) {
      meReq = $.getJSON('/api/qms/me').done(function (u) {
        var role = u.role === 'REVIEWER' ? '품질 담당자' : '조회 전용';
        $('#me').text(u.displayName === role ? role : u.displayName + ' · ' + role);
      });
    }
    return meReq;
  }

  window.QG = { csrf: csrf, esc: esc, errorText: errorText, time: time, label: label, labelWithCode: labelWithCode,
    stateTag: stateTag, tone: tone, confirm: confirmDialog, me: me, LABELS: LABELS };

  renderShell();
}(jQuery));
