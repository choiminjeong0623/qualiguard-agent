package com.qualiguard.qms.quality.stream;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qualiguard.qms.bridge.TelemetryMessage;
import com.qualiguard.qms.quality.Anomaly;
import com.qualiguard.qms.quality.CleanShot;
import com.qualiguard.qms.quality.EquipmentState;
import com.qualiguard.qms.quality.MewmaMonitor;
import com.qualiguard.qms.quality.Shot;
import com.qualiguard.qms.quality.SpcEngine;

import io.micrometer.core.instrument.Metrics;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka Streams 프로세서: sensor.raw 1건 → (중복 제거·전처리·SPC 판정) → 정제 데이터 + 이상 탐지 결과.
 *
 * 설비별 상태(EquipmentState)는 Kafka Streams 상태 저장소(RocksDB)에 두고, 변경 내역은 Kafka 의
 * changelog 토픽에도 기록된다. 그래서 앱을 재시작해도 기준값(μ, σ)과 진행 중인 판정이 이어진다.
 *
 * 출력은 값 앞에 "C|"(정제 데이터) 또는 "A|"(이상) 표시를 붙여 내보내고, 토폴로지에서 두 토픽으로 나눈다.
 */
public class SpcProcessor implements Processor<String, String, String, String> {

    public static final String STORE = "spc-equipment-state";
    public static final String CLEAN = "C|";
    public static final String ANOMALY = "A|";

    private static final Logger log = LoggerFactory.getLogger(SpcProcessor.class);

    private final ObjectMapper objectMapper;
    private final SpcEngine engine = new SpcEngine();
    /** 다변량 감시 (shadow). null 이면 끔 */
    private final MewmaMonitor mewma;

    private ProcessorContext<String, String> context;
    private KeyValueStore<String, String> store;

    public SpcProcessor(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public SpcProcessor(ObjectMapper objectMapper, MewmaMonitor mewma) {
        this.objectMapper = objectMapper;
        this.mewma = mewma;
    }

    @Override
    public void init(ProcessorContext<String, String> context) {
        this.context = context;
        this.store = context.getStateStore(STORE);
    }

    /**
     * 설비 메시지 → SPC 입력. 탐지 평가기(DetectionEval)도 같은 변환을 쓰도록 꺼내 둔다 (로드맵 E2).
     * 결측 허용(ADR-017): sensors 가 없으면 빈 맵, 값이 없는 센서는 SpcEngine 이 invalid 로 처리한다.
     * 검사 결과가 없거나 result 가 비면 '검사 안 함' — OK 로 세지 않는다.
     */
    public static Shot toShot(TelemetryMessage t) {
        Map<String, Double> values = new LinkedHashMap<>();
        t.sensorsOrEmpty().forEach((key, sv) -> values.put(key, sv == null ? null : sv.value()));
        boolean inspected = t.inspection() != null && t.inspection().result() != null;
        boolean ng = inspected && "NG".equals(t.inspection().result());
        String defectType = inspected ? t.inspection().defectType() : null;
        return new Shot(t.messageId(), t.lineId(), t.equipmentId(), t.shotNo(), t.ts(), values, ng, defectType,
                inspected, t.serialNo());
    }

    @Override
    public void process(Record<String, String> record) {
        TelemetryMessage t;
        try {
            t = objectMapper.readValue(record.value(), TelemetryMessage.class);
        } catch (RuntimeException e) {
            // 브리지에서 이미 검증했으므로 정상적으로는 오지 않는다
            log.warn("sensor.raw 메시지 해석 실패 — 건너뜀: {}", e.toString());
            return;
        }

        String equipmentId = t.equipmentId();
        EquipmentState state = load(equipmentId);
        Shot shot = toShot(t);
        boolean inspected = shot.inspected();
        boolean ng = shot.ng();
        String defectType = shot.defectType();

        SpcEngine.Result result = engine.process(state, shot);
        if (result.duplicate()) {
            log.debug("중복 shot 무시: {}", t.messageId());
            Metrics.counter("qg.spc.duplicate_shots", "equipment", equipmentId).increment();
            return;
        }
        store.put(equipmentId, objectMapper.writeValueAsString(state));
        if (result.late()) {
            // 순서가 어긋난 shot: 실시간 흐름에는 넣지 않고 시간 순서 자리에 끼워 따로 다시 판정 (ADR-019)
            log.info("늦게 온 shot 재판정({}): {} shotNo={} ts={} → 경보 {}건", result.lateStatus(), equipmentId,
                    t.shotNo(), t.ts(), result.anomalies().size());
            Metrics.counter("qg.spc.late_shots", "equipment", equipmentId, "status", result.lateStatus()).increment();
        }
        for (String sensor : result.outageStarted()) {
            // 경보(이상 탐지)로 만들지 않고 운영 지표·로그로만 알린다
            log.warn("[센서 끊김] {} {} 값이 {} shot 연속 비어 있음", equipmentId, sensor, SpcEngine.OUTAGE_SHOTS);
            Metrics.counter("qg.spc.sensor_outage", "equipment", equipmentId, "sensor", sensor).increment();
        }
        for (String sensor : result.outageEnded()) {
            log.info("[센서 회복] {} {} 값이 다시 들어옴", equipmentId, sensor);
        }

        if (mewma != null && !result.late()) {
            shadowMewma(equipmentId, t.shotNo(), state, result);
        }

        CleanShot clean = new CleanShot(t.messageId(), t.lineId(), equipmentId, t.shotNo(), t.ts(),
                result.cleanValues(), result.invalidSensors(), inspected ? (ng ? "NG" : "OK") : null, defectType,
                t.serialNo(), result.late(), result.lateStatus(), t.lotNo());
        context.forward(new Record<>(equipmentId, CLEAN + objectMapper.writeValueAsString(clean), record.timestamp()));

        for (Anomaly anomaly : result.anomalies()) {
            log.info("[이상 탐지] {} ({})", anomaly.message(), anomaly.ruleName());
            context.forward(new Record<>(equipmentId, ANOMALY + objectMapper.writeValueAsString(anomaly),
                    record.timestamp()));
        }
    }

    /** 다변량 감시 결과는 로그·지표로만 남긴다 (경보 토픽·에이전트로 보내지 않음). 상태는 메모리라 재시작하면 기준부터 다시 배운다. */
    private void shadowMewma(String equipmentId, Long shotNo, EquipmentState state, SpcEngine.Result result) {
        long n = shotNo != null ? shotNo : state.count;
        MewmaMonitor.Alarm a = mewma.add(equipmentId, n, result.cleanValues());
        if (a != null) {
            log.info("[다변량 shadow] {} shot {} T²={} > {} 기여 센서 {}", equipmentId, n,
                    String.format(java.util.Locale.ROOT, "%.1f", a.t2()), a.limit(), a.contributions());
            Metrics.counter("qg.spc.mewma_shadow_alarms", "equipment", equipmentId, "sensor", a.topSensor())
                    .increment();
        }
    }

    private EquipmentState load(String equipmentId) {
        String json = store.get(equipmentId);
        if (json == null) {
            return new EquipmentState();
        }
        try {
            return objectMapper.readValue(json, EquipmentState.class);
        } catch (RuntimeException e) {
            log.warn("{} 상태 복원 실패 — 기준 구간부터 다시 시작: {}", equipmentId, e.toString());
            return new EquipmentState();
        }
    }
}
