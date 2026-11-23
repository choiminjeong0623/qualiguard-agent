package com.qualiguard.qms.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import com.qualiguard.qms.agent.AnalysisResult;
import com.qualiguard.qms.agent.IncidentAnalyzer;
import com.qualiguard.qms.agent.ToolObservations;
import com.qualiguard.qms.agent.VerifierAgent;
import com.qualiguard.qms.agent.ml.CauseForest;
import com.qualiguard.qms.agent.ml.MlTriage;
import com.qualiguard.qms.agent.ml.MlTriageStats;
import com.qualiguard.qms.agent.TokenBudget;
import com.qualiguard.qms.agent.pipeline.AgentMetrics;
import com.qualiguard.qms.agent.pipeline.Incident;
import com.qualiguard.qms.agent.pipeline.IncidentStore;
import com.qualiguard.qms.ncr.NcrReviewService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DB 초기화 스크립트 통합 시험 (로드맵 Q3). 실제 SQL Server 2025 컨테이너에서 확인한다.
 *
 * 1) 빈 DB 에 infra/mssql/init/*.sql 을 docker-compose 의 mssql-init 과 똑같이(sqlcmd -b, 이름 순) 실행 → 모두 성공
 *    (F1 콜드 스타트 버그처럼 앞 번호 스크립트가 뒤 번호 객체를 먼저 쓰면 여기서 실패한다)
 * 2) 한 번 더 실행해도 성공 (모든 스크립트가 "없을 때만 생성")
 * 3) 승인 프로시저: 확정 원인 없이 승인 거부, 반려는 사유 필수, 승인하면 이력 + 아웃박스 이벤트가 같은 트랜잭션에 생김
 * 4) 하루 토큰 예산(O5) 집계 SQL: 서울 0시 경계, UTC 로 기록된 시각, NULL 토큰
 * 5) AI 초안 검토 지표(P3): 그대로 승인 / 고쳐서 승인을 이력으로 구분
 * 6) 운영 리포트 SQL(O3, scripts/ops_report.sql): 실패율·지연 P50·P95·되돌림율·단계별 시간
 * 7) 앱 전용 계정(Q5, app_login.sqlcmd): 읽기·쓰기·프로시저 실행은 되고 표 생성·삭제(DDL)는 거부
 * 8) ML 1차 분류(실험): DB 에 넣은 shot 으로 파이썬과 같은 원인, LLM 없이 만든 결과가 검증 에이전트 통과,
 *    판단 기록(agent_ml_judgment)과 일치율 집계
 *
 * 실행: cd qms-app && ./gradlew containerTest  (Docker 필요, CI 의 별도 작업에서 돈다)
 */
@Tag("container")
@Testcontainers
class MssqlInitScriptsContainerTest {

    private static final String PASSWORD = "Qg-Test-Passw0rd!";
    private static final Path INIT = Path.of("../infra/mssql/init");
    private static final String APP_USER = "qg_app";
    private static final String APP_PASSWORD = "Qg-App-Passw0rd!";

    @Container
    static final GenericContainer<?> MSSQL = new GenericContainer<>("mcr.microsoft.com/mssql/server:2025-latest")
            .withEnv("ACCEPT_EULA", "Y")
            .withEnv("MSSQL_SA_PASSWORD", PASSWORD)
            .withExposedPorts(1433)
            .withCopyFileToContainer(MountableFile.forHostPath(INIT), "/init")
            .withCopyFileToContainer(MountableFile.forHostPath("../scripts/ops_report.sql"), "/scripts/ops_report.sql")
            .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client connections.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static List<String> firstRun;

    @BeforeAll
    static void 빈_DB_에_초기화_스크립트를_모두_실행한다() throws Exception {
        waitUntilLoginWorks();
        firstRun = runAllInitScripts();
    }

    @Test
    void 모든_초기화_스크립트가_빈_DB_에서_성공한다() throws Exception {
        List<String> files;
        try (Stream<Path> s = Files.list(INIT)) {
            files = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sql")).sorted().toList();
        }
        assertThat(firstRun).containsExactlyElementsOf(files);
        try (Connection c = connect(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT COL_LENGTH('dbo.agent_analysis', 'prompt_version')")) {
            rs.next();
            assertThat(rs.getObject(1)).as("마지막 번호대 스크립트까지 적용됨").isNotNull();
        }
    }

    @Test
    void 앱_전용_계정은_읽기_쓰기_프로시저만_되고_DDL_은_거부된다() throws Exception {
        for (int i = 0; i < 2; i++) {   // 두 번째 실행은 비밀번호만 다시 맞춘다
            ExecResult r = sqlcmd("-v", "QG_DB_APP_USER=" + APP_USER, "QG_DB_APP_PASSWORD=" + APP_PASSWORD,
                    "-i", "/init/app_login.sqlcmd");
            assertThat(r.getExitCode()).as("app_login.sqlcmd:\n" + r.getStdout() + r.getStderr()).isZero();
        }
        try (Connection c = DriverManager.getConnection(jdbcUrl(), APP_USER, APP_PASSWORD);
                Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dbo.equipment")) {
                assertThat(rs.next()).isTrue();
            }
            insertDraft(c, "IT-Q5-01", "체크 링 마모", "CHECK_RING_WEAR");   // 쓰기
            review(c, "IT-Q5-01", "APPROVE", "앱 계정으로 승인");             // 프로시저 실행
            assertThat(status(c, "IT-Q5-01")).isEqualTo("APPROVED");
            assertThatThrownBy(() -> st.execute("CREATE TABLE dbo.q5_probe (id INT)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission");
            assertThatThrownBy(() -> st.execute("DROP TABLE dbo.ncr_history"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("ALTER PROCEDURE dbo.usp_KnowledgeStats AS SELECT 1"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void 한_번_더_실행해도_성공한다() throws Exception {
        assertThat(runAllInitScripts()).hasSameSizeAs(firstRun);
    }

    @Test
    void 확정_원인_없이는_승인할_수_없다() throws Exception {
        try (Connection c = connect()) {
            insertDraft(c, "IT-0001", null, null);
            assertThatThrownBy(() -> review(c, "IT-0001", "APPROVE", null))
                    .isInstanceOf(SQLException.class).hasMessageContaining("확정 원인");
            assertThat(status(c, "IT-0001")).isEqualTo("DRAFT");
        }
    }

    @Test
    void 반려는_사유가_있어야_한다() throws Exception {
        try (Connection c = connect()) {
            insertDraft(c, "IT-0002", null, null);
            assertThatThrownBy(() -> review(c, "IT-0002", "REJECT", " "))
                    .isInstanceOf(SQLException.class).hasMessageContaining("반려 사유");
            review(c, "IT-0002", "REJECT", "센서 문제로 확인");
            assertThat(status(c, "IT-0002")).isEqualTo("REJECTED");
            assertThat(count(c, "SELECT COUNT(*) FROM dbo.integration_outbox o JOIN dbo.ncr n ON n.ncr_id = o.ncr_id "
                    + "WHERE n.ncr_no = 'IT-0002'")).as("반려는 MES/ERP 로 보내지 않는다").isZero();
        }
    }

    @Test
    void 승인하면_이력과_아웃박스_이벤트가_함께_생긴다() throws Exception {
        try (Connection c = connect()) {
            insertDraft(c, "IT-0003", "히터 밴드 열화로 실린더 온도 하락", "HEATER_DEGRADATION");
            review(c, "IT-0003", "APPROVE", "확인");
            assertThat(status(c, "IT-0003")).isEqualTo("APPROVED");
            assertThat(count(c, "SELECT COUNT(*) FROM dbo.ncr_history h JOIN dbo.ncr n ON n.ncr_id = h.ncr_id "
                    + "WHERE n.ncr_no = 'IT-0003' AND h.action = 'APPROVE' AND h.to_status = 'APPROVED'")).isEqualTo(1);
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT o.topic, o.msg_key, JSON_VALUE(o.payload, '$.ncrNo'), JSON_VALUE(o.payload, '$.causeCode'),
                           o.published_at
                    FROM dbo.integration_outbox o JOIN dbo.ncr n ON n.ncr_id = o.ncr_id WHERE n.ncr_no = 'IT-0003'
                    """); ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("quality.ncr.approved");
                assertThat(rs.getString(2)).isEqualTo("IMM-01");
                assertThat(rs.getString(3)).isEqualTo("IT-0003");
                assertThat(rs.getString(4)).isEqualTo("HEATER_DEGRADATION");
                assertThat(rs.getObject(5)).as("발행 전").isNull();
                assertThat(rs.next()).as("이벤트는 한 건").isFalse();
            }
            assertThatThrownBy(() -> review(c, "IT-0003", "APPROVE", null))
                    .isInstanceOf(SQLException.class).hasMessageContaining("DRAFT");
        }
    }

    // ── 보조 ─────────────────────────────────────────────────────────

    /** mssql-init 과 같은 명령. 실패하면 그 파일과 출력을 담아 바로 멈춘다. 실행한 파일 이름을 돌려준다. */
    private static List<String> runAllInitScripts() throws Exception {
        List<String> files;
        try (Stream<Path> s = Files.list(INIT)) {
            files = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sql")).sorted().toList();
        }
        for (String f : files) {
            ExecResult r = sqlcmd("-i", "/init/" + f);
            assertThat(r.getExitCode()).as(f + " 실행 실패:\n" + r.getStdout() + r.getStderr()).isZero();
        }
        return files;
    }

    private static ExecResult sqlcmd(String... args) throws Exception {
        String[] base = { "/opt/mssql-tools18/bin/sqlcmd", "-S", "localhost", "-U", "sa", "-P", PASSWORD, "-C", "-I",
                "-b" };
        String[] cmd = new String[base.length + args.length];
        System.arraycopy(base, 0, cmd, 0, base.length);
        System.arraycopy(args, 0, cmd, base.length, args.length);
        return MSSQL.execInContainer(cmd);
    }

    private static void waitUntilLoginWorks() throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (sqlcmd("-Q", "SELECT 1").getExitCode() != 0) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("SQL Server 로그인 대기 시간 초과");
            }
            Thread.sleep(1000);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), "sa", PASSWORD);
    }

    private static String jdbcUrl() {
        return "jdbc:sqlserver://" + MSSQL.getHost() + ":" + MSSQL.getMappedPort(1433)
                + ";databaseName=QUALIGUARD;encrypt=true;trustServerCertificate=true";
    }

    @Test
    void 하루_토큰_예산은_서울_0시부터_센다() throws Exception {
        try (Connection c = connect()) {
            insertTokens(c, "2026-11-22T23:59:00+09:00", 500_000, 0, 0);   // 어제 (서울) → 빼야 함
            insertTokens(c, "2026-11-22T15:01:00+00:00", 300_000, 50_000, 20_000); // = 오늘 00:01 서울, UTC 로 기록
            insertTokens(c, "2026-11-23T10:00:00+09:00", 40_000, 5_000, null);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
            var clock = Clock.fixed(Instant.parse("2026-11-23T05:00:00Z"), ZoneOffset.UTC);   // 서울 14:00
            var budget = new TokenBudget(jdbc, new TokenBudget.Settings(400_000L, ZoneId.of("Asia/Seoul")), clock);

            TokenBudget.Status s = budget.status();
            assertThat(s.day()).hasToString("2026-11-23");
            assertThat(s.used()).isEqualTo(415_000);
            assertThat(s.exceeded()).isTrue();
            assertThat(budget.allowsAuto()).isFalse();
        }
    }

    @Test
    void AI_초안_검토_지표는_이력에서_원문을_되살려_센다() throws Exception {
        try (Connection c = connect()) {
            var service = new NcrReviewService(new JdbcTemplate(new SingleConnectionDataSource(c, true)),
                    JsonMapper.builder().build());
            Map<String, Object> before = service.reviewStats();

            insertDraft(c, "IT-P3-01", "히터 열화", "HEATER_DEGRADATION");   // 그대로 승인
            // 담당자·기한 지정은 사람 몫이라 "AI 초안을 고침"으로 세지 않는다 (P2)
            service.assign("IT-P3-01", "통합 시험", Map.of("actionOwner", "김반장", "actionDueDate", "2026-12-01"));
            service.approve("IT-P3-01", "통합 시험", null);
            insertDraft(c, "IT-P3-02", null, "HEATER_DEGRADATION");          // 사람이 원인을 고쳐 승인
            service.update("IT-P3-02", "통합 시험", Map.of("rootCause", "냉각수 배관 막힘", "causeCode", "COOLING_ISSUE"));
            service.approve("IT-P3-02", "통합 시험", null);

            Map<String, Object> after = service.reviewStats();
            assertThat((int) after.get("approved") - (int) before.get("approved")).isEqualTo(2);
            assertThat((int) after.get("approvedAsIs") - (int) before.get("approvedAsIs")).isEqualTo(1);
            assertThat(after.get("editRate")).isNotNull();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 필수_항목_검사가_상세와_집계에_붙는다() throws Exception {
        try (Connection c = connect()) {
            var service = new NcrReviewService(new JdbcTemplate(new SingleConnectionDataSource(c, true)),
                    JsonMapper.builder().build());
            insertDraft(c, "IT-P2-01", null, "UNKNOWN");   // 원인·근거·조치·센서·불량유형·경보 없음
            @SuppressWarnings("unchecked")
            Map<String, Object> comp = (Map<String, Object>) service.detail("IT-P2-01").get("completeness");
            assertThat(comp).containsEntry("total", 11);
            assertThat((List<Object>) comp.get("missing"))
                    .contains("원인 코드", "근거", "영향 범위", "담당자", "조치 기한");

            // 사람이 담당자·기한·영향 로트를 정하면 빠짐에서 사라진다 (승인 전·후 모두 가능)
            Map<String, Object> d = service.assign("IT-P2-01", "통합 시험", Map.of("actionOwner", "성형1팀 김반장",
                    "actionDueDate", "2026-11-30", "affectedLots", "L261123-IMM-01-03 , L261123-IMM-01-04"));
            assertThat(d).containsEntry("action_owner", "성형1팀 김반장").containsEntry("action_due_date", "2026-11-30")
                    .containsEntry("affected_lots", "L261123-IMM-01-03,L261123-IMM-01-04");
            assertThat((List<Object>) ((Map<String, Object>) d.get("completeness")).get("missing"))
                    .doesNotContain("담당자", "조치 기한", "영향 범위");
            assertThatThrownBy(() -> service.assign("IT-P2-01", "통합 시험", Map.of("actionDueDate", "11/30")))
                    .hasMessageContaining("yyyy-MM-dd");
            // 빈 값은 칸을 비운다
            d = service.assign("IT-P2-01", "통합 시험", Map.of("actionOwner", ""));
            assertThat(d.get("action_owner")).isNull();
            assertThat(d.get("action_due_date")).isEqualTo("2026-11-30");

            Map<String, Object> stats = service.completenessStats();
            assertThat((int) stats.get("count")).isPositive();
            assertThat(stats.get("items")).isInstanceOf(Map.class);
        }
    }

    @Test
    void 영향_로트는_첫_경보부터_분석_시점까지의_로트() throws Exception {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO dbo.process_shot (message_id, line_id, equipment_id, shot_no, shot_ts, inspection_result, lot_no)
                    VALUES (?, 'LINE1', 'IMM-02', ?, DATEADD(SECOND, ?, CAST('2026-11-23T10:00:00+09:00' AS DATETIMEOFFSET(3))), 'OK', ?)
                    """)) {
                for (int shot = 240; shot <= 520; shot += 10) {   // 240~250 → 01, 260~500 → 02, 510~520 → 03
                    ps.setString(1, "it-lot-" + shot);
                    ps.setLong(2, shot);
                    ps.setInt(3, shot * 30);
                    ps.setString(4, String.format("L261123-IMM-02-%02d", (shot - 1) / 250 + 1));
                    ps.executeUpdate();
                }
            }
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
            // 첫 경보 shot 250, 분석 기준 shot 510 → 로트 01·02·03
            var job = new IncidentAnalyzer.Job("ALARM", "IMM-02", "a-1", "2026-11-23T12:05:00+09:00",
                    List.of(Map.of("shot_no", 250L), Map.of("shot_no", 300L)), List.of("a-1"), true, Map.of(),
                    "2026-11-23T14:15:00+09:00", 510L, null, null, null, null);
            assertThat(IncidentAnalyzer.affectedLots(jdbc, job))
                    .isEqualTo("L261123-IMM-02-01,L261123-IMM-02-02,L261123-IMM-02-03");
            // 재생처럼 경보 shot 만 보면 그 로트 하나
            var single = new IncidentAnalyzer.Job("ALARM", "IMM-02", "a-2", "2026-11-23T12:30:00+09:00",
                    List.of(Map.of("shot_no", 300L)), List.of("a-2"), true, Map.of(),
                    "2026-11-23T12:30:00+09:00", 300L, null, null, null, null);
            assertThat(IncidentAnalyzer.affectedLots(jdbc, single)).isEqualTo("L261123-IMM-02-02");
        }
    }

    @Test
    void 재시작_뒤_끝나지_않은_사건을_기록에서_되살린다() throws Exception {
        String checkpoint = """
                {"v":1,"job":{"trigger":"ALARM","equipmentId":"IMM-01","seedAnomalyId":"a-r1",
                 "seedTs":"2026-11-23T10:00:00+09:00","alarms":[{"shot_no":300,"rule_code":"XBAR"}],
                 "anomalyIds":["a-r1"],"ownsAlarmStatus":true,"seedEvidence":{},"asOf":"2026-11-23T10:00:00+09:00",
                 "asOfShot":300,"previousBefore":null,"parentId":null,"tag":null,"recheckInfo":null},
                 "triage":{"severity":"HIGH","type":"QUALITY_IMPACT","alarms":1,"sensors":["barrelTemp"],"reason":"r"},
                 "state":"VERIFYING","elapsedMs":4200,"attempts":1,"basePrompt":"p","prompt":"p",
                 "result":{"decision":"NCR_DRAFT","causeCode":"HEATER_DEGRADATION","confidence":80,"title":"t",
                   "phenomenon":"현상","suspectedCause":"추정","evidence":["온도 224.1"],"similarCases":[],
                   "recommendedAction":"점검","reasoningSummary":"요약","watchItems":[],"candidateCauses":[]},
                 "error":null,"verification":null,"verifiedAttempt":0,"written":null,"report":null,
                 "lastAttemptTrace":["getSpcSummary(IMM-01) @1ms"],"observed":["224.1"],
                 "trace":["getSpcSummary(IMM-01) @1ms"],
                 "timeline":[{"atMs":0,"topic":"agent.incident.triaged","state":"TRIAGED"},
                             {"atMs":10,"topic":"agent.incident.triaged","state":"ANALYZING"},
                             {"atMs":4100,"topic":"agent.analysis.completed","state":"VERIFYING"}]}
                """;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dbo.agent_incident "
                    + "(incident_id, equipment_id, trigger_type, state, checkpoint_json) VALUES (?, 'IMM-01', 'ALARM', ?, ?)")) {
                ps.setString(1, "it-resume-1");
                ps.setString(2, "VERIFYING");
                ps.setString(3, checkpoint);
                ps.executeUpdate();
                ps.setString(1, "it-resume-old");   // 이 기능 전의 사건: 기록 없음 → 실패로 닫힘
                ps.setString(2, "ANALYZING");
                ps.setString(3, null);
                ps.executeUpdate();
                ps.setString(1, "it-resume-done");  // 끝난 사건은 대상 아님
                ps.setString(2, "AWAITING_APPROVAL");
                ps.setString(3, checkpoint);
                ps.executeUpdate();
            }
            var store = new IncidentStore(new JdbcTemplate(new SingleConnectionDataSource(c, true)),
                    AgentMetrics.none(), JsonMapper.builder().build());
            List<Incident> restored = store.restore();
            assertThat(restored).extracting(Incident::id).containsExactly("it-resume-1");
            assertThat(restored.get(0).state()).isEqualTo(Incident.State.VERIFYING);
            assertThat(store.inFlight()).hasSize(1);
            try (ResultSet rs = st.executeQuery("SELECT state FROM dbo.agent_incident WHERE incident_id = 'it-resume-old'")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("FAILED");
            }
        }
    }

    @Test
    void ML_1차_분류는_DB_의_shot_으로_파이썬과_같은_원인을_내고_검증을_통과한다() throws Exception {
        var mapper = JsonMapper.builder().build();
        JsonNode fixture;
        CauseForest forest;
        try (InputStream in = getClass().getResourceAsStream("/ml/parity.json")) {
            fixture = mapper.readTree(in);
        }
        try (InputStream in = getClass().getResourceAsStream("/ml/cause-forest.json")) {
            forest = CauseForest.load(in, mapper);
        }
        // 학습한 고장으로 확신한 경보 하나 (scikit-learn 확률 0.9 이상)
        JsonNode alarm = null;
        for (JsonNode a : fixture.path("alarms")) {
            double max = 0;
            for (JsonNode p : a.path("proba")) {
                max = Math.max(max, p.asDouble());
            }
            if (MlTriage.KNOWN_FAULTS.contains(a.get("label").asString()) && !"SENSOR_FAULT".equals(a.get("label").asString())
                    && max >= 0.9) {
                alarm = a;
                break;
            }
        }
        assertThat(alarm).as("시험 데이터에 확신한 고장 경보가 있어야 함").isNotNull();
        String eq = alarm.get("eq").asString();
        long shot = alarm.get("shot").asLong();
        String base = "2026-11-20T08:00:00+09:00";   // 다른 시험의 shot(11-23)과 겹치지 않는 날
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO dbo.process_shot (message_id, line_id, equipment_id, shot_no, shot_ts, mold_temp, barrel_temp,
                        injection_pressure, holding_pressure, cushion, cycle_time, motor_current, inspection_result, defect_type)
                    VALUES (?, 'LINE1', ?, ?, DATEADD(SECOND, ?, CAST(? AS DATETIMEOFFSET(3))), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (JsonNode row : fixture.path("shots").path(eq)) {
                    long n = row.get(0).asLong();
                    ps.setString(1, "it-ml-" + eq + "-" + n);
                    ps.setString(2, eq);
                    ps.setLong(3, n);
                    ps.setLong(4, n * 10);
                    ps.setString(5, base);
                    for (int j = 0; j < 7; j++) {
                        JsonNode v = row.get(1).get(j);
                        ps.setObject(6 + j, v == null || v.isNull() ? null : v.decimalValue());
                    }
                    ps.setString(13, row.get(2).isNull() ? null : row.get(2).asString());
                    ps.setString(14, row.get(3).isNull() ? null : row.get(3).asString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            c.setAutoCommit(true);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
            String asOf = jdbc.queryForObject("SELECT CONVERT(VARCHAR(33), shot_ts, 127) FROM dbo.process_shot "
                    + "WHERE message_id = ?", String.class, "it-ml-" + eq + "-" + shot);
            var job = new IncidentAnalyzer.Job("ALARM", eq, "a-ml", asOf,
                    List.of(Map.of("shot_no", shot, "rule_code", alarm.get("rule").asString(),
                            "sensor_key", alarm.get("sensor").asString())),
                    List.of("a-ml"), true, Map.of(), asOf, shot, null, null, null, null);
            MlTriage ml = new MlTriage(jdbc, forest, MlTriage.Mode.SKIP, 0.9);
            MlTriage.Prediction p = ml.predict(job).orElseThrow();
            assertThat(p.cause()).isEqualTo(alarm.get("label").asString());
            int best = forest.classes().indexOf(p.cause());
            assertThat(p.probability()).isCloseTo(alarm.get("proba").get(best).asDouble(), within(0.02));
            assertThat(ml.skipLlm(p)).isTrue();

            // LLM 없이 만든 결과도 검증 에이전트를 똑같이 거친다: 조회 도구 호출 + 도구 값과 맞는 수치 근거
            List<String> trace = new java.util.ArrayList<>();
            var observed = new ToolObservations();
            AnalysisResult r = ml.result(job, p, System.nanoTime(), trace, observed);
            assertThat(r.decision()).isEqualTo("NCR_DRAFT");
            assertThat(r.evidence()).hasSizeGreaterThanOrEqualTo(2);
            var v = new VerifierAgent(jdbc).verify(new VerifierAgent.Input(eq, asOf,
                    List.of(alarm.get("rule").asString()), r, trace, observed));
            assertThat(v.fails()).as("근거 %s / 도구 %s", r.evidence(), trace).isEmpty();
            assertThat(v.numberVerified()).isEqualTo(v.numberClaims());
            assertThat(p.unfamiliar()).as("학습 데이터와 같은 시나리오의 경보는 낯설지 않다 (거리 %s / 한계 %s)",
                    p.novelty(), p.noveltyLimit()).isFalse();

            // 판단 기록과 일치율 집계 (shadow 로 LLM 과 같은 원인이었다고 기록, 같은 사건은 한 번만)
            MlTriage shadow = new MlTriage(jdbc, forest, MlTriage.Mode.SHADOW, 0.9);
            shadow.record("it-ml-inc-1", job, p, MlTriage.Route.SHADOW, p.cause());
            shadow.record("it-ml-inc-1", job, p, MlTriage.Route.SHADOW, "NOISE");
            ml.record("it-ml-inc-2", job, p, MlTriage.Route.SKIPPED, null);
            Map<String, Object> stats = new MlTriageStats(jdbc).stats(7);
            assertThat(stats.get("incidents")).isEqualTo(2);
            assertThat(stats.get("wouldSkip")).isEqualTo(1.0);
            @SuppressWarnings("unchecked")
            Map<String, Object> vsLlm = (Map<String, Object>) stats.get("vsLlm");
            assertThat(vsLlm.get("wouldSkipCompared")).isEqualTo(1);
            assertThat(vsLlm.get("wouldSkipAgreement")).isEqualTo(1.0);
        }
    }

    @Test
    void 운영_리포트_SQL_이_돌고_지연_P95_와_되돌림율을_낸다() throws Exception {
        String tag = "it-ops";
        try (Connection c = connect()) {
            // 분석 4건: 지연 10·20·30·40초, 1건 되돌린 뒤 통과, 1건 실패
            insertRun(c, tag, "NCR_DRAFT", 10_000, 1, "PASS", 1000, 100);
            insertRun(c, tag, "NCR_DRAFT", 20_000, 2, "PASS", 2000, 200);
            insertRun(c, tag, "OBSERVE", 30_000, 1, "PASS", 1000, 100);
            insertRun(c, tag, "FAILED", null, 1, null, 0, 0);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO dbo.agent_incident (incident_id, equipment_id, trigger_type, run_tag, state) "
                        + "VALUES ('it-ops-1', 'IMM-01', 'ALARM', '" + tag + "', 'AWAITING_APPROVAL')");
                st.executeUpdate("INSERT INTO dbo.agent_incident_event (incident_id, topic, state, created_at) VALUES "
                        + "('it-ops-1', 't', 'TRIAGED', '2026-11-23T10:00:00+09:00'),"
                        + "('it-ops-1', 't', 'ANALYZING', '2026-11-23T10:00:01+09:00'),"
                        + "('it-ops-1', 't', 'VERIFYING', '2026-11-23T10:00:41+09:00'),"
                        + "('it-ops-1', 't', 'AWAITING_APPROVAL', '2026-11-23T10:00:42+09:00')");
            }
        }
        ExecResult r = sqlcmd("-W", "-s", "|", "-v", "Days=3650", "RunTag=" + tag, "InUsd=1", "OutUsd=10",
                "-i", "/scripts/ops_report.sql");
        String out = r.getStdout();
        assertThat(r.getExitCode()).as(out + r.getStderr()).isZero();
        assertThat(out).contains("== 1)", "== 2)", "== 3)", "== 4)");
        // 1) 4건 중 실패 1 (25.0%), 성공 3건 지연 10·20·30초 → P50 20000, P95 29000 (선형 보간)
        assertThat(out).containsPattern("\\|4\\|1\\|25\\.0\\|20000\\|29000\\|");
        // 2) 성공 3건 중 되돌림 1 (33.3%), 되돌린 뒤 통과 100%
        assertThat(out).containsPattern("3\\|1\\|33\\.3\\|1\\|100\\.0\\|3\\|0");
        // 3) 분석 단계 40초
        assertThat(out).containsPattern("ANALYZING\\|1\\|40000\\|40000");
    }

    private static void insertRun(Connection c, String tag, String decision, Integer latencyMs, int attempts,
            String verify, int inTok, int outTok) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO dbo.agent_analysis (equipment_id, seed_anomaly_id, anomaly_ids, decision, latency_ms,
                    attempts, verify_status, prompt_tokens, output_tokens, model, prompt_version, run_tag)
                VALUES ('IMM-01', 'it', 'it', ?, ?, ?, ?, ?, ?, 'test-model', 'analysis-v1@test', ?)
                """)) {
            ps.setString(1, decision);
            ps.setObject(2, latencyMs);
            ps.setInt(3, attempts);
            ps.setString(4, verify);
            ps.setInt(5, inTok);
            ps.setInt(6, outTok);
            ps.setString(7, tag);
            ps.executeUpdate();
        }
    }

    private static void insertTokens(Connection c, String at, Integer prompt, Integer output, Integer thought)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO dbo.agent_analysis (equipment_id, seed_anomaly_id, anomaly_ids, decision,
                                                prompt_tokens, output_tokens, thought_tokens, created_at)
                VALUES ('IMM-01', 'it-seed', 'it-seed', 'OBSERVE', ?, ?, ?, CAST(? AS DATETIMEOFFSET(3)))
                """)) {
            ps.setObject(1, prompt);
            ps.setObject(2, output);
            ps.setObject(3, thought);
            ps.setString(4, at);
            ps.executeUpdate();
        }
    }

    private static void insertDraft(Connection c, String ncrNo, String rootCause, String causeCode)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO dbo.ncr (ncr_no, equipment_id, title, phenomenon, root_cause, cause_code, created_by, occurred_at)
                VALUES (?, 'IMM-01', N'통합 시험', N'실린더 온도 하락', ?, ?, 'AGENT', SYSDATETIMEOFFSET())
                """)) {
            ps.setString(1, ncrNo);
            ps.setString(2, rootCause);
            ps.setString(3, causeCode);
            ps.executeUpdate();
        }
    }

    private static void review(Connection c, String ncrNo, String decision, String comment) throws SQLException {
        try (CallableStatement cs = c.prepareCall("{call dbo.usp_ReviewNcr(?, ?, ?, ?)}")) {
            cs.setString(1, ncrNo);
            cs.setString(2, decision);
            cs.setString(3, "통합 시험");
            cs.setString(4, comment);
            cs.execute();
        }
    }

    private static String status(Connection c, String ncrNo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM dbo.ncr WHERE ncr_no = ?")) {
            ps.setString(1, ncrNo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
