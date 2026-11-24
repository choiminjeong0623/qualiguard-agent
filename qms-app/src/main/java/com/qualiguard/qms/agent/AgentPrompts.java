package com.qualiguard.qms.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 에이전트 시스템 프롬프트 (로드맵 O1: 프롬프트 버전 관리).
 * 본문은 resources/prompts/*.md 에 두고, 어떤 프롬프트로 낸 판단인지 agent_analysis.prompt_version 에 남긴다.
 * 버전: "파일 이름@본문 해시 앞 8자리". 크게 바꾸면 새 파일(analysis-v3.md …)을 만들고, 이전 판은 지우지 않는다.
 * 원인-센서 대응표는 일부러 넣지 않는다. 과거 사례를 도구로 찾아 추론하게 한다.
 */
final class AgentPrompts {

    /** 기본 분석 프롬프트 (E6 오답 분류로 만든 v2). qg.agent.prompt=analysis-v1 이면 이전 판으로 비교할 수 있다. */
    static final String DEFAULT_FILE = "analysis-v2";
    static final String REPAIR_FILE = "repair-v1";

    /** 형식 복구 프롬프트 (분석 응답이 JSON 으로 읽히지 않을 때, 도구 없이 형식만 바로잡아 달라고 요청). */
    static final String REPAIR = load(REPAIR_FILE);

    /** 한 번에 쓰는 프롬프트 묶음. version 은 agent_analysis.prompt_version 과 /api/agent/config 에 남긴다. */
    record Set(String file, String system, String repair, String version) {
    }

    static final Set DEFAULT = of(DEFAULT_FILE);
    static final String SYSTEM = DEFAULT.system();
    static final String VERSION = DEFAULT.version();

    /** 분석 프롬프트 파일 이름으로 묶음을 만든다. 버전 = "파일 이름@본문 해시 앞 8자리" (형식 복구 프롬프트까지 함께 묶음). */
    static Set of(String file) {
        String system = load(file);
        return new Set(file, system, REPAIR, file + "@" + shortHash(system + "\u0000" + REPAIR));
    }

    static String load(String name) {
        String path = "/prompts/" + name + ".md";
        try (InputStream in = AgentPrompts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("프롬프트 파일이 없습니다: classpath:" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("프롬프트 파일을 읽지 못함: " + path, e);
        }
    }

    static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 은 모든 JDK 에 있다
        }
    }

    private AgentPrompts() {
    }
}
