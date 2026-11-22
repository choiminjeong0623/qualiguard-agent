package com.qualiguard.qms.agent;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 분석 에이전트가 도구로 "실제로 본" 수치를 모아 두는 바구니.
 *
 * QualityTools 가 DB 결과를 모델에게 돌려줄 때마다 같은 값을 여기에도 넣는다.
 * 검증 에이전트는 분석 결과의 근거 문장에 적힌 수치가 이 바구니에 있는지 대조한다
 * (모델이 바꿀 수 없는 기록과 맞춰 보는 것이라, 같은 모델에게 다시 묻는 검증보다 독립적이다).
 *
 * 한 사건의 모든 시도(되돌림 포함)가 같은 바구니를 쓴다. 재분석 때 앞선 시도에서 본 값을 다시 인용해도
 * 기준 시점(asOf)이 같아 여전히 유효한 값이기 때문이다.
 */
public final class ToolObservations {

    /** 너무 많은 값을 모으면 아무 수나 우연히 맞게 되므로 상한을 둔다. */
    static final int MAX_VALUES = 5000;

    // 같은 값을 여러 번 넣어도 한 번만 남는다. 여러 스레드(도구 호출)가 동시에 넣을 수 있어 synchronized 로 감싼다.
    private final Set<BigDecimal> values = new LinkedHashSet<>();

    /** 도구 결과 행들. 숫자 칸은 그대로, 글자 칸(과거 사례 근거 문장 등)은 안의 수치를 뽑아 넣는다. */
    public void addRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            row.values().forEach(this::addValue);
        }
    }

    /** 도구에 넘긴 인자 (최근 300 shot, 20 shot 씩 등). 근거에 조회 범위를 적어도 틀린 수치로 보지 않게 한다. */
    public void addArgs(Object... args) {
        for (Object a : args) {
            addValue(a);
        }
    }

    /** 값 하나 (숫자, 숫자 글자, 수치가 든 문장). */
    public void addValue(Object v) {
        if (v instanceof Number n) {
            add(EvidenceNumbers.toDecimal(n));
        } else if (v instanceof String s) {
            BigDecimal whole = EvidenceNumbers.parseOrNull(s);
            if (whole != null) {
                add(whole);                       // "224.13" 처럼 숫자만 든 글자
            } else {
                EvidenceNumbers.extract(s).forEach(c -> add(c.value()));   // "당시 온도 218℃" 같은 문장
            }
        }
    }

    private synchronized void add(BigDecimal d) {
        if (d != null && values.size() < MAX_VALUES) {
            // 224.1300 과 224.13 을 같은 값으로 두려고 끝의 0 을 떼되, 300 이 3E+2 로 바뀌지 않게 소수 자릿수는 0 이상으로
            BigDecimal clean = d.stripTrailingZeros();
            values.add(clean.scale() < 0 ? clean.setScale(0) : clean);
        }
    }

    public synchronized List<BigDecimal> values() {
        return List.copyOf(values);
    }

    public synchronized boolean isEmpty() {
        return values.isEmpty();
    }

    /** 단위 시험용: 값 목록으로 바로 만든다. */
    public static ToolObservations of(Collection<? extends Number> numbers) {
        ToolObservations o = new ToolObservations();
        numbers.forEach(o::addValue);
        return o;
    }
}
