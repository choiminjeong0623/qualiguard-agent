package com.qualiguard.qms.quality;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.qualiguard.qms.bridge.TelemetryMessage;
import com.qualiguard.qms.quality.stream.SpcProcessor;

import tools.jackson.databind.json.JsonMapper;

/**
 * 시뮬레이터 파일 출력(telemetry.jsonl)을 운영과 같은 SpcEngine 에 재생해 경보를 alarms.csv 로 남긴다.
 * ML 원인 분류 기준선(scripts/ml_cause_baseline.py)이 "에이전트가 받는 것과 같은 경보"를 입력으로 쓰게 하려는 것이다.
 *
 * 사용: ./gradlew alarmExport --args="<seed 폴더> [<seed 폴더> ...]"   (scripts/ml_baseline.sh 가 부른다)
 * 열: equipmentId, shotNo, ts, rule, sensor, recentNgRate, zScores(JSON), defectTypes(JSON)
 */
public final class AlarmExport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AlarmExport() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("seed 폴더를 하나 이상 주세요");
        }
        for (String a : args) {
            Path dir = Path.of(a);
            int n = export(dir);
            System.out.println(dir + ": 경보 " + n + "건 → alarms.csv");
        }
    }

    static int export(Path dir) throws IOException {
        SpcEngine engine = new SpcEngine();
        Map<String, EquipmentState> states = new HashMap<>();
        int count = 0;
        try (Writer w = Files.newBufferedWriter(dir.resolve("alarms.csv"), StandardCharsets.UTF_8)) {
            w.write("equipmentId,shotNo,ts,rule,sensor,recentNgRate,zScores,defectTypes\n");
            for (String line : Files.readAllLines(dir.resolve("telemetry.jsonl"), StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Shot shot = SpcProcessor.toShot(JSON.readValue(line, TelemetryMessage.class));
                SpcEngine.Result r = engine.process(
                        states.computeIfAbsent(shot.equipmentId(), k -> new EquipmentState()), shot);
                for (Anomaly x : r.anomalies()) {
                    w.write(String.join(",", x.equipmentId(), Long.toString(x.shotNo()), x.ts(), x.rule(),
                            x.sensor() == null ? "" : x.sensor(), Double.toString(x.recentNgRate()),
                            csv(JSON.writeValueAsString(x.sensorZScores() == null ? Map.of() : x.sensorZScores())),
                            csv(JSON.writeValueAsString(x.recentDefectTypes() == null ? Map.of() : x.recentDefectTypes()))));
                    w.write('\n');
                    count++;
                }
            }
        }
        return count;
    }

    /** JSON 을 CSV 한 칸에: 큰따옴표로 감싸고 안의 큰따옴표는 두 번. */
    private static String csv(String s) {
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
