package com.qualiguard.qms.agent.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.qualiguard.qms.agent.VerifierAgent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트 사이 전달 방식 비교 (로드맵 A2): direct(같은 스레드) vs Kafka(agent.* 토픽).
 *
 * LLM 은 즉시 답하는 가짜로 바꾸고(분석 시간 0), 사건 하나가 분류 → 분석 → 검증 → 작성 → 종결까지 가는
 * 전체 시간을 N건 재서 P50·P95·최대를 낸다. 즉 순수한 "전달 비용"만 남는다.
 * Kafka 는 앱과 같은 설정(acks=all, 멱등, linger 5ms)의 생산자 + 소비자 1개로 흉내 낸다.
 * 앱은 에이전트마다 소비자 그룹이 따로지만(KafkaEventBus) 한 사건의 단계는 어차피 차례로 진행돼 지연은 비슷하다.
 *
 *   docker compose up -d kafka kafka-init       # 토픽 준비
 *   ./gradlew transportBench                     # → eval/transport_report.md
 *   ./gradlew transportBench --args="localhost:9092 500 ../eval/transport_report.md"
 *
 * 장애 복구는 재지 않는다: 재시작 복구는 전달 방식과 무관하게 DB 사건 기록으로 한다 (IncidentStore.restore,
 * IncidentRecoveryTest). 이 사실을 보고서에 적는다.
 */
public final class TransportBench {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final List<String> TOPICS = List.of(AgentTopics.TRIAGED, AgentTopics.ANALYSIS_REQUESTED,
            AgentTopics.ANALYSIS_COMPLETED, AgentTopics.VERIFICATION_REQUESTED, AgentTopics.VERIFICATION_COMPLETED,
            AgentTopics.REPORT_REQUESTED, AgentTopics.REPORT_COMPLETED);

    private TransportBench() {
    }

    /** 결과 한 줄. */
    record Stats(String transport, int n, double p50, double p95, double max, double mean, int failed) {
    }

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        Path out = Path.of(args.length > 2 ? args[2] : "../eval/transport_report.md");

        Stats direct = run("direct", n, router -> new DirectEventBus(router), () -> { });
        System.out.println(direct);
        Stats kafka;
        try (KafkaBus bus = new KafkaBus(bootstrap)) {
            kafka = run("kafka", n, bus::attach, bus::awaitReady);
        }
        System.out.println(kafka);
        String md = report(direct, kafka, bootstrap);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, md);
        System.out.println("저장: " + out);
    }

    interface BusFactory {
        EventBus create(AgentEventRouter router);
    }

    static Stats run(String name, int n, BusFactory factory, Runnable ready) {
        var analyzer = new AgentPipelineTest.FakeAnalyzer();
        int warmup = Math.min(20, n);
        for (int k = 0; k < n + warmup; k++) {
            analyzer.answers.add(AgentPipelineTest.draft(78, List.of("온도 224.1℃", "불량률 6%")));
        }
        var router = new AgentEventRouter();
        EventBus bus = factory.create(router);
        var store = new IncidentStore(null, new AgentMetrics(null));
        new AnalysisAgent(analyzer, store, bus, router);
        new VerifierStage(new VerifierAgent(new AgentPipelineTest.NoFacts()), analyzer, store, bus, router);
        new ReportWriterAgent(new ReportWriterAgent.Settings("analysis-text", "http://x", "m", 5), null, null, JSON,
                store, bus, router);
        new IncidentOrchestrator(analyzer, store, bus, JSON, 1, router, false);
        var pipeline = new AgentPipeline(new TriageAgent(analyzer, store, bus), store, Duration.ofSeconds(30));
        ready.run();

        List<Double> ms = new ArrayList<>();
        int failed = 0;
        for (int k = 0; k < n + warmup; k++) {
            long t0 = System.nanoTime();
            var o = pipeline.analyzeAndWait("bench-" + k, true, "bench");
            double took = (System.nanoTime() - t0) / 1e6;
            if (k < warmup) {
                continue;   // JIT·연결 준비 시간은 빼고 잰다
            }
            if (o == null || !"NCR_DRAFT".equals(o.decision())) {
                failed++;
            }
            ms.add(took);
        }
        double[] xs = ms.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return new Stats(name, xs.length, pct(xs, 0.5), pct(xs, 0.95), xs[xs.length - 1],
                Arrays.stream(xs).average().orElse(0), failed);
    }

    static double pct(double[] sorted, double q) {
        double k = (sorted.length - 1) * q;
        int lo = (int) Math.floor(k);
        int hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (k - lo);
    }

    static String report(Stats d, Stats k, String bootstrap) {
        String f = "| %s | %d | %.2f | %.2f | %.2f | %.2f | %d |%n";
        StringBuilder sb = new StringBuilder();
        sb.append("# 에이전트 전달 방식 비교 (로드맵 A2)\n\n");
        sb.append("- 측정: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(", `./gradlew transportBench` (Kafka ").append(bootstrap).append(")\n");
        sb.append("- 실행 환경: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch"))
                .append(", CPU ").append(Runtime.getRuntime().availableProcessors()).append("개, Java ")
                .append(System.getProperty("java.version"))
                .append(" (Kafka 는 docker compose 단일 브로커. 컴퓨터마다 값이 다르니 맥에서 다시 재면 이 파일을 덮어씀)\n");
        sb.append("- LLM 은 즉시 답하는 가짜. 사건 1건 = 분류 → 분석 → 검증 → 작성 → 종결 (이벤트 7개)의 전체 시간\n\n");
        sb.append("| 전달 방식 | 사건 수 | P50 (ms) | P95 (ms) | 최대 (ms) | 평균 (ms) | 실패 |\n|---|---|---|---|---|---|---|\n");
        sb.append(String.format(Locale.ROOT, f, d.transport(), d.n(), d.p50(), d.p95(), d.max(), d.mean(), d.failed()));
        sb.append(String.format(Locale.ROOT, f, k.transport(), k.n(), k.p50(), k.p95(), k.max(), k.mean(), k.failed()));
        sb.append(String.format(Locale.ROOT, "%n- Kafka 가 더하는 시간(P50): 사건당 약 %.1f ms. 실제 분석 1단계는 평균 40초 이상(LLM)이라 "
                + "전체 대비 비중은 %.3f%% 수준. 이벤트 7개마다 생산자 linger.ms=5(묶어 보내기 대기)와 acks=all 확인을 "
                + "기다리므로 대부분이 그 몫입니다%n", k.p50() - d.p50(), (k.p50() - d.p50()) / 40000 * 100));
        sb.append("- 장애 복구: 재시작 복구는 전달 방식과 무관하게 DB 사건 기록(agent_incident.checkpoint_json)으로 합니다 (2026-11-23, 결정 C). 앱이 다시 켜지면 끝나지 않은 사건을 되살려 멈춘 단계의 요청을 다시 보내고, 이미 끝난 일은 다시 하지 않습니다. Kafka 는 기본값으로 유지합니다: 에이전트를 다른 프로세스로 떼어 낼 때의 토픽 계약과, 프로세스가 나뉘었을 때 보낸 이벤트를 잃지 않는 전달(최소 한 번)을 위해서입니다.\n");
        sb.append("- 판단 기록: ADR-016 '3단계: 전달 방식과 재시작 복구'.\n");
        return sb.toString();
    }

    /** 앱과 같은 생산자 설정 + 모든 agent.* 토픽을 읽는 소비자 1개. */
    static final class KafkaBus implements EventBus, AutoCloseable {
        private final KafkaProducer<String, String> producer;
        private final KafkaConsumer<String, String> consumer;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean assigned = new AtomicBoolean(false);
        private AgentEventRouter router;
        private Thread loop;

        KafkaBus(String bootstrap) {
            Properties p = new Properties();
            p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            p.put(ProducerConfig.ACKS_CONFIG, "all");
            p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
            p.put(ProducerConfig.LINGER_MS_CONFIG, "5");
            p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            producer = new KafkaProducer<>(p);
            Properties c = new Properties();
            c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            c.put(ConsumerConfig.GROUP_ID_CONFIG, "qg-transport-bench-" + UUID.randomUUID());
            c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
            c.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
            c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            consumer = new KafkaConsumer<>(c);
        }

        EventBus attach(AgentEventRouter r) {
            this.router = r;
            loop = new Thread(this::poll, "transport-bench-consumer");
            loop.setDaemon(true);
            loop.start();
            return this;
        }

        void awaitReady() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!assigned.get()) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Kafka 소비자가 파티션을 받지 못했습니다 (토픽: docker compose up kafka-init)");
                }
                sleep(100);
            }
        }

        private void poll() {
            consumer.subscribe(TOPICS);
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(50));
                if (!consumer.assignment().isEmpty() && !assigned.get()) {
                    consumer.seekToEnd(consumer.assignment());
                    consumer.assignment().forEach(consumer::position);   // 끝 위치를 확정한 뒤 준비 완료
                    assigned.set(true);
                    continue;
                }
                for (ConsumerRecord<String, String> r : records) {
                    router.route(r.topic(), JSON.readValue(r.value(), AgentEvent.class));
                }
            }
            consumer.close();
        }

        @Override
        public void publish(String topic, AgentEvent event) {
            try {
                producer.send(new ProducerRecord<>(topic, event.incidentId(), JSON.writeValueAsString(event)))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("Kafka 전송 실패 (" + topic + "): " + e, e);
            }
        }

        @Override
        public void close() {
            running.set(false);
            if (loop != null) {
                try {
                    loop.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            producer.close(Duration.ofSeconds(5));
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
