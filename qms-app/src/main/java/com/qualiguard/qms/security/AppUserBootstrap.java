package com.qualiguard.qms.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 시작할 때 .env 의 QG_REVIEWER_* / QG_VIEWER_* 값으로, DB 에 없는 계정만 만든다.
 *
 * - 비밀번호는 저장소에 두지 않는다 (.env 는 커밋되지 않음). 비밀번호가 비어 있으면 그 계정은 만들지 않는다.
 * - 이미 있는 계정의 비밀번호는 바꾸지 않는다 (재시작해도 사용자가 바꾼 값 유지).
 * - 계정이 하나도 없으면 로그인 방법을 로그로 안내한다.
 */
@Component
public class AppUserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AppUserBootstrap.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Environment env;

    public AppUserBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder, Environment env) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            create("REVIEWER", "QG_REVIEWER", "reviewer", "품질 담당자");
            create("VIEWER", "QG_VIEWER", "viewer", "조회 전용");
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.app_user WHERE enabled = 1", Integer.class);
            if (count == null || count == 0) {
                log.warn("[로그인] QMS 화면 계정이 없습니다. .env 에 QG_REVIEWER_PASSWORD 를 넣고 다시 시작하세요 (.env.example 참고).");
            }
        } catch (RuntimeException e) {
            log.warn("[로그인] 계정 준비를 건너뜀 (09_users.sql 적용 여부 확인): {}", e.getMessage());
        }
    }

    /**
     * .env 를 properties 로 읽으면(spring.config.import ...[.properties]) ISO-8859-1 로 읽혀 한글이 깨진다.
     * 글자가 모두 0~255 범위이고 그 바이트가 올바른 UTF-8 이면 UTF-8 로 다시 읽는다.
     * 실제 환경 변수처럼 처음부터 올바른 한글은 0~255 범위를 넘으므로 그대로 둔다.
     */
    static String utf8FromLatin1(String s) {
        if (s == null || s.chars().allMatch(c -> c < 0x80) || s.chars().anyMatch(c -> c > 0xFF)) {
            return s;
        }
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return s;   // UTF-8 로 읽히지 않으면 원래 값 (예: 진짜 라틴 문자 이름)
        }
    }

    /** 계정이 없고 비밀번호가 주어졌으면 만든다. 만들었으면 true. */
    boolean create(String role, String prefix, String defaultUser, String defaultName) {
        String password = env.getProperty(prefix + "_PASSWORD", "");
        if (password.isBlank()) {
            return false;
        }
        String username = env.getProperty(prefix + "_USERNAME", defaultUser).strip();
        String raw = env.getProperty(prefix + "_NAME", defaultName).strip();
        String name = utf8FromLatin1(raw);
        if (!name.equals(raw)) {
            // 예전에 깨진 채 저장된 이름을 바로잡는다 (비밀번호는 건드리지 않음)
            int fixed = jdbc.update("UPDATE dbo.app_user SET display_name = ? WHERE username = ? AND display_name = ?",
                    name, username, raw);
            if (fixed > 0) {
                log.info("[로그인] 계정 표시 이름의 한글 깨짐을 바로잡았습니다: {}", username);
            }
        }
        int rows = jdbc.update("""
                INSERT INTO dbo.app_user (username, display_name, password_hash, role)
                SELECT ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM dbo.app_user WHERE username = ?)
                """, username, name, encoder.encode(password), role, username);
        if (rows > 0) {
            log.info("[로그인] 계정을 만들었습니다: {} ({})", username, role);
        }
        return rows > 0;
    }
}
