package com.qualiguard.qms.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.qualiguard.qms.quality.CleanShot;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * DB 적재의 결측·중복 처리 (ADR-017). 실제 SQL Server 의 고유 인덱스 동작은 로컬 E2E 로 확인했다.
 */
class QualityStoreWriterTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final QualityStoreWriter writer = new QualityStoreWriter(jdbc, json, meters);

    private String cleanShot(String id) {
        return json.writeValueAsString(new CleanShot(id, "LINE1", "IMM-01", null, "2026-11-17T09:00:00.000+09:00",
                Map.of("moldTemp", 60.0), List.of("barrelTemp"), null, null, null, true, "OUT_OF_WINDOW"));
    }

    private double count(String result) {
        var c = meters.find("qg.store.records").tag("type", "shot").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void shotNo_검사결과_시리얼이_없어도_NULL_로_적재한다() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        writer.onCleanShot(cleanShot("m-1"));
        assertEquals(1, count("saved"));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        Object[] a = args.getValue();
        assertEquals(19, a.length);
        assertNull(a[3]);                         // shot_no
        assertNull(a[12]);                        // inspection_result
        assertNull(a[15]);                        // serial_no
        assertTrue((Boolean) a[16]);              // arrival_late
        assertEquals("OUT_OF_WINDOW", a[17]);     // late_status (ADR-019)
    }

    @Test
    void 이미_저장된_메시지는_건너뛰고_중복으로_센다() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        writer.onCleanShot(cleanShot("m-2"));
        writer.onCleanShot(cleanShot("m-2"));
        assertEquals(1, count("saved"));
        assertEquals(1, count("duplicate"));
    }

    @Test
    void 동시에_들어와_고유_인덱스에_걸려도_실패하지_않는다() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("ux_process_shot_message"));
        writer.onCleanShot(cleanShot("m-3"));
        assertEquals(1, count("duplicate"));
    }

    @Test
    void 긴_시리얼은_40자로_자른다() {
        assertEquals(40, QualityStoreWriter.serial("X".repeat(60)).length());
        assertNull(QualityStoreWriter.serial(null));
        assertEquals("TMP-1", QualityStoreWriter.serial("TMP-1"));
    }

}
