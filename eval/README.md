# 평가 기록 (eval/)

문서(README, `docs/evaluation.md`)에 적는 수치는 **이 폴더에 커밋된 결과 파일에서만** 가져옵니다.
`data/` 는 커밋하지 않는 작업 폴더라, 그곳의 파일만으로는 나중에 수치를 다시 확인할 수 없기 때문입니다 (로드맵 E1).

## 구조

```
eval/results/<날짜-시각>_<git 커밋>[-dirty]_<모드>/
  meta.json            실행 조건: git 커밋, 실행 인자, 에이전트 설정(/api/agent/config: 모델·온도·전달 방식·작성 방식·벡터 검색 여부),
                       채점에 쓴 정답의 sha256·행 수, 시뮬레이터 메타(seed·설정 해시·고장 시나리오)
  results.json         사례·회차별 원본 결과 (scripts/evaluate_agent.py 출력과 같음)
  summary.md           요약 표
  ground_truth.csv.gz  채점에 쓴 정답 파일 (시뮬레이터 출력)
```

- 모드: `base`(기본), `hidden`(정답 원인 과거 사례 숨김), `novel`(과거 사례 + 정비 이력 숨김)
- 폴더 이름에 `-dirty` 가 붙으면 커밋하지 않은 변경이 있는 상태에서 잰 값입니다. 문서에 인용하지 마세요.

## 정답 파일 관리

- 시뮬레이터는 실행할 때 기존 `data/ground_truth.csv` 를 덮어쓰지 않고 `data/ground_truth_archive/` 로 옮기고,
  새 정답 옆에 `ground_truth.meta.json`(seed·설정 해시·고장 시나리오)을 남깁니다.
- `scripts/evaluate_agent.py` 는 정답 파일이 고장 구간(IMM-01 shot 1799, IMM-02 shot 1399)까지 담고 있지 않으면 바로 멈춥니다.
  짧은 시험 실행으로 덮어쓴 정답으로 채점하는 일을 막기 위해서입니다.
