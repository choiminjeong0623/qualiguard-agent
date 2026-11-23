package com.qualiguard.qms.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * DB 초기화 스크립트 통합 시험 (로드맵 Q3). 실제 SQL Server 2025 컨테이너에서 확인한다.
 *
 * 1) 빈 DB 에 infra/mssql/init/*.sql 을 docker-compose 의 mssql-init 과 똑같이(sqlcmd -b, 이름 순) 실행 → 모두 성공
 *    (F1 콜드 스타트 버그처럼 앞 번호 스크립트가 뒤 번호 객체를 먼저 쓰면 여기서 실패한다)
 * 2) 한 번 더 실행해도 성공 (모든 스크립트가 "없을 때만 생성")
 * 3) 승인 프로시저: 확정 원인 없이 승인 거부, 반려는 사유 필수, 승인하면 이력 + 아웃박스 이벤트가 같은 트랜잭션에 생김
 *
 * 실행: cd qms-app && ./gradlew containerTest  (Docker 필요, CI 의 별도 작업에서 돈다)
 */
@Tag("container")
@Testcontainers
class MssqlInitScriptsContainerTest {

    private static final String PASSWORD = "Qg-Test-Passw0rd!";
    private static final Path INIT = Path.of("../infra/mssql/init");

    @Container
    static final GenericContainer<?> MSSQL = new GenericContainer<>("mcr.microsoft.com/mssql/server:2025-latest")
            .withEnv("ACCEPT_EULA", "Y")
            .withEnv("MSSQL_SA_PASSWORD", PASSWORD)
            .withExposedPorts(1433)
            .withCopyFileToContainer(MountableFile.forHostPath(INIT), "/init")
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
        return DriverManager.getConnection("jdbc:sqlserver://" + MSSQL.getHost() + ":" + MSSQL.getMappedPort(1433)
                + ";databaseName=QUALIGUARD;encrypt=true;trustServerCertificate=true", "sa", PASSWORD);
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
