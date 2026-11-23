package com.qualiguard.qms.quality.stream;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import com.qualiguard.qms.bridge.KafkaTopics;
import com.qualiguard.qms.quality.MewmaMonitor;

import tools.jackson.databind.ObjectMapper;

/**
 * 품질 파이프라인 토폴로지 (Kafka Streams).
 *
 *   sensor.raw ──SpcProcessor(상태 저장소: 설비별 SPC 상태)──┬─ "C|" → sensor.clean      (정제 데이터)
 *                                                          └─ "A|" → quality.anomaly   (이상 탐지 → 에이전트 입력)
 *
 * application.yml 의 spring.kafka.streams.* 설정(application-id, exactly_once_v2 등)을 사용한다.
 *
 * qg.quality.mewma.mode = shadow 면 SPC 옆에서 다변량 이상 탐지(MEWMA)를 함께 계산해 로그·지표로만 남긴다
 * (경보 토픽으로 보내지 않음). 기본 off. 근거: eval/mewma_report.md
 */
@Configuration
@EnableKafkaStreams
public class QualityStreamsConfig {

    @Bean
    public KStream<String, String> qualityPipeline(StreamsBuilder builder, ObjectMapper objectMapper,
            @Value("${qg.quality.mewma.mode:off}") String mewmaMode,
            @Value("${qg.quality.mewma.lambda:0.2}") double mewmaLambda,
            @Value("${qg.quality.mewma.limit:35.26}") double mewmaLimit) {
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(SpcProcessor.STORE), Serdes.String(), Serdes.String()));

        // 프로세서는 파티션(작업)마다 만들어지지만 설비는 한 파티션에만 있으므로 감시기 하나를 같이 써도 된다
        MewmaMonitor mewma = shadow(mewmaMode) ? new MewmaMonitor(mewmaLambda, mewmaLimit) : null;
        ProcessorSupplier<String, String, String, String> spc = () -> new SpcProcessor(objectMapper, mewma);

        KStream<String, String> out = builder
                .stream(KafkaTopics.SENSOR_RAW, Consumed.with(Serdes.String(), Serdes.String()))
                .process(spc, SpcProcessor.STORE);

        out.filter((key, value) -> value.startsWith(SpcProcessor.CLEAN))
                .mapValues(value -> value.substring(SpcProcessor.CLEAN.length()))
                .to(KafkaTopics.SENSOR_CLEAN, Produced.with(Serdes.String(), Serdes.String()));

        out.filter((key, value) -> value.startsWith(SpcProcessor.ANOMALY))
                .mapValues(value -> value.substring(SpcProcessor.ANOMALY.length()))
                .to(KafkaTopics.QUALITY_ANOMALY, Produced.with(Serdes.String(), Serdes.String()));

        return out;
    }

    /** off / shadow. YAML 의 따옴표 없는 off 는 false 로 읽힌다. */
    static boolean shadow(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (m.equals("shadow")) {
            return true;
        }
        if (m.isEmpty() || m.equals("off") || m.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException("qg.quality.mewma.mode 는 off 또는 shadow: " + mode);
    }
}
