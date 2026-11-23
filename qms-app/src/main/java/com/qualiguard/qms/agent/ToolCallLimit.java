package com.qualiguard.qms.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 분석 1회(LLM 호출 1번) 안에서 모델이 부를 수 있는 도구 호출 수 상한 (docs/agent/guardrail-policy.md G-03).
 *
 * 모델이 같은 조회를 되풀이하며 결론을 미루는 경우를 막는다. 상한을 넘은 호출은 DB 를 조회하지 않고
 * "더 조회하지 말고 지금까지의 근거로 결론을 내라"는 오류를 돌려준다. 모델이 그래도 계속 부르면
 * LLM 호출 시간 제한(qg.agent.llm.timeout)이 마지막 상한이 된다.
 *
 * QualityTools·SopTools 가 같은 객체를 나눠 쓰고, LLM 재시도마다 reset() 으로 처음부터 센다.
 * max 가 0 이하이면 상한 없음.
 */
public final class ToolCallLimit {

    public static final ToolCallLimit NONE = new ToolCallLimit(0);

    private final int max;
    private final AtomicInteger used = new AtomicInteger();

    public ToolCallLimit(int max) {
        this.max = max;
    }

    /** 호출 1회를 센다. 상한 안이면 true. */
    boolean tryAcquire() {
        int n = used.incrementAndGet();
        return max <= 0 || n <= max;
    }

    public void reset() {
        used.set(0);
    }

    public int used() {
        return used.get();
    }

    public int max() {
        return max;
    }

    /** 상한을 넘은 호출에 돌려줄 결과. trace 에도 한 줄 남긴다. */
    List<Map<String, Object>> rejected(List<String> trace, String tool) {
        trace.add("  ↳ 도구 호출 상한 " + max + "회 초과: " + tool + " 조회하지 않음");
        return List.of(Map.of("error", "도구 호출 상한(" + max + "회)에 도달했습니다. "
                + "더 조회하지 말고 지금까지 확인한 근거로 최종 JSON 을 출력하세요."));
    }
}
