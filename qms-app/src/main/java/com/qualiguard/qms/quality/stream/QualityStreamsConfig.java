package com.qualiguard.qms.quality.stream;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.state.Stores;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import com.qualiguard.qms.bridge.KafkaTopics;

import tools.jackson.databind.ObjectMapper;

/**
 * 품질 파이프라인 토폴로지 (Kafka Streams).
 *
 *   sensor.raw ──SpcProcessor(상태 저장소: 설비별 SPC 상태)──┬─ "C|" → sensor.clean      (정제 데이터)
 *                                                          └─ "A|" → quality.anomaly   (이상 탐지 → 에이전트 입력)
 *
 * application.yml 의 spring.kafka.streams.* 설정(application-id, exactly_once_v2 등)을 사용한다.
 */
@Configuration
@EnableKafkaStreams
public class QualityStreamsConfig {

    @Bean
    public KStream<String, String> qualityPipeline(StreamsBuilder builder, ObjectMapper objectMapper) {
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(SpcProcessor.STORE), Serdes.String(), Serdes.String()));

        ProcessorSupplier<String, String, String, String> spc = () -> new SpcProcessor(objectMapper);

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
}
