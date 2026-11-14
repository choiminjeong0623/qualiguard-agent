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

        Map<String, Double> values = new LinkedHashMap<>();
        t.sensors().forEach((key, sv) -> values.put(key, sv == null ? null : sv.value()));
        boolean ng = t.inspection() != null && "NG".equals(t.inspection().result());
        String defectType = t.inspection() == null ? null : t.inspection().defectType();
        Shot shot = new Shot(t.messageId(), t.lineId(), equipmentId, t.shotNo(), t.ts(), values, ng, defectType);

        SpcEngine.Result result = engine.process(state, shot);
        if (result.duplicate()) {
            log.debug("중복 shot 무시: {}", t.messageId());
            return;
        }
        store.put(equipmentId, objectMapper.writeValueAsString(state));

        CleanShot clean = new CleanShot(t.messageId(), t.lineId(), equipmentId, t.shotNo(), t.ts(),
                result.cleanValues(), result.invalidSensors(), ng ? "NG" : "OK", defectType);
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
