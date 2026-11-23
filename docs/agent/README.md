# 에이전트 설계·평가 산출물

원인 분석 에이전트(도구 호출, 기억, 검증, 가드레일)에 특화된 산출물 목록입니다. 각 문서는 코드 경로와 근거 파일을 함께 적습니다.

| 단계 | 산출물 | 문서 |
|---|---|---|
| 설계 | 에이전트 워크플로우 (상태·시퀀스) | [사건 상태](../architecture/incident-state.mmd) · [파이프라인](../architecture/agent-pipeline.mmd) · [도구 호출 루프](tool-spec.md#4-도구-호출-루프-분석-1회-안) |
| 설계 | 툴·API 명세 | [tool-spec.md](tool-spec.md) |
| 설계 | 시스템 프롬프트 정의·변경 이력 | [prompt-changelog.md](prompt-changelog.md) |
| 지식·기억 | RAG (유사 사례 벡터 검색, SOP BM25) | [tool-spec.md 2.4·2.6](tool-spec.md) · [ADR-014](../decisions/ADR-014-ncr-vector-search.md) |
| 지식·기억 | 메모리 아키텍처 | [memory-architecture.md](memory-architecture.md) |
| 검증 | 평가 데이터셋·시나리오 | [evaluation.md](../evaluation.md) · [eval/](../../eval/README.md) |
| 안전성 | 가드레일·폴백 정책 | [guardrail-policy.md](guardrail-policy.md) |
