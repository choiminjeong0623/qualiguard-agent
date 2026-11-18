# notebooks — Kaggle Learn 복습 실습

Kaggle Learn(Pandas · Data Cleaning · Intermediate ML)에서 배운 개념을 QualiGuard의 보조 검증 데이터인 **UCI SECOM**(반도체 공정 센서 590개 × 1,567건)에 적용한 노트북입니다. 실습은 구글 코랩에서 진행했습니다.

| 노트북 | 내용 | 다시 본 레슨 |
|---|---|---|
| [`kaggle_02_secom_missing_scaling.ipynb`](kaggle_02_secom_missing_scaling.ipynb) | 결측치 처리 방법 6가지를 같은 랜덤 포레스트·5겹 교차검증(ROC-AUC)으로 비교하고, 스케일링(MinMax·Standard·Robust)과 정규화(Box-Cox·Yeo-Johnson)가 모델별로 미치는 영향을 실험 | Data Cleaning — Handling Missing Values, Scaling and Normalization |

## 실습 2 핵심 결과

- **결측치는 지우지 않고 채운다.** 행 삭제는 0행, 결측 있는 센서 전부 삭제는 AUC 약 0.52. 50% 넘게 빈 센서와 값이 하나뿐인 센서만 지우고 중앙값으로 채운다(채우는 방법끼리의 차이는 흔들림 범위 안).
- **스케일링·정규화는 모델에 맞춘다.** 트리 모델(RF, XGBoost, Isolation Forest)에는 필요 없고, KNN·로지스틱 회귀에는 필요하다. 쏠림이 심한 SECOM에서는 Yeo-Johnson이 Standard보다 나았다(KNN 0.642 → 0.682).
- **QualiGuard 연결 (모델은 아직 정하지 않음)**: 지금 QualiGuard의 이상 탐지는 SPC 관리도 규칙이다. ML 판정을 더한다면 모델에 따라 전처리를 다르게 한다(트리 계열은 결측 처리만, 거리 기반 KNN·LOF는 Yeo-Johnson 후). 이번 결과는 SECOM 기준이므로, 어떤 모델을 쓸지는 주력 데이터인 사출성형 합성 데이터로 같은 기준(5겹 교차검증 AUC)으로 비교한 뒤 정한다.

## 실행 방법

데이터는 커밋하지 않습니다(`data/`는 `.gitignore`).

- **로컬**: `./scripts/fetch_secom.sh`로 `data/secom/`에 받은 뒤, 노트북 첫 셀의 `DATA_DIR`을 `'../data/secom'`으로 바꿉니다.
- **코랩**: 구글 드라이브 `내 드라이브/kaggle-learn/secom/`에 `secom.data`, `secom_labels.data`를 올리고 첫 셀(드라이브 마운트)부터 실행합니다.

노트북에 저장된 실행 결과는 scikit-learn 버전에 따라 소수 셋째 자리에서 조금 다를 수 있습니다.
