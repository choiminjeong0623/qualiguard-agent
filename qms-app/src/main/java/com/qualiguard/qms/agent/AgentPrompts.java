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
 * 버전: "파일 이름@본문 해시 앞 8자리". 크게 바꾸면 analysis-v2.md 를 새로 만든다.
 * 원인-센서 대응표는 일부러 넣지 않는다. 과거 사례를 도구로 찾아 추론하게 한다.
 */
final class AgentPrompts {

    static final String ANALYSIS_FILE = "analysis-v1";
    static final String REPAIR_FILE = "repair-v1";

    static final String SYSTEM = load(ANALYSIS_FILE);

    /** 분석 응답이 JSON 으로 읽히지 않을 때, 도구 없이 형식만 바로잡아 달라고 요청하는 프롬프트. */
    static final String REPAIR = load(REPAIR_FILE);

    /** agent_analysis.prompt_version 과 /api/agent/config 에 남기는 값. 형식 복구 프롬프트까지 함께 묶는다. */
    static final String VERSION = ANALYSIS_FILE + "@" + shortHash(SYSTEM + "\u0000" + REPAIR);

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
