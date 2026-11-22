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

    private ProcessorContext<String, String> context;
    private KeyValueStore<String, String> store;

    public SpcProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void init(ProcessorContext<String, String> context) {
        this.context = context;
        this.store = context.getStateStore(STORE);
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

        // 결측 허용(ADR-017): sensors 가 없으면 빈 맵, 값이 없는 센서는 SpcEngine 이 invalid 로 처리한다
        Map<String, Double> values = new LinkedHashMap<>();
        t.sensorsOrEmpty().forEach((key, sv) -> values.put(key, sv == null ? null : sv.value()));
        // 검사 결과가 없거나 result 가 비면 '검사 안 함' — OK 로 세지 않는다
        boolean inspected = t.inspection() != null && t.inspection().result() != null;
        boolean ng = inspected && "NG".equals(t.inspection().result());
        String defectType = inspected ? t.inspection().defectType() : null;
        Shot shot = new Shot(t.messageId(), t.lineId(), equipmentId, t.shotNo(), t.ts(), values, ng, defectType,
                inspected, t.serialNo());

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

        CleanShot clean = new CleanShot(t.messageId(), t.lineId(), equipmentId, t.shotNo(), t.ts(),
                result.cleanValues(), result.invalidSensors(), inspected ? (ng ? "NG" : "OK") : null, defectType,
                t.serialNo(), result.late(), result.lateStatus());
        context.forward(new Record<>(equipmentId, CLEAN + objectMapper.writeValueAsString(clean), record.timestamp()));

        for (Anomaly anomaly : result.anomalies()) {
            log.info("[이상 탐지] {} ({})", anomaly.message(), anomaly.ruleName());
            context.forward(new Record<>(equipmentId, ANOMALY + objectMapper.writeValueAsString(anomaly),
                    record.timestamp()));
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
