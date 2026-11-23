package com.qualiguard.qms.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * MQTT TLS 접속 (로드맵 Q5). 브로커 인증서를 서명한 CA 인증서(PEM)만 믿는 소켓을 만든다.
 * 개발용 자체 CA 는 scripts/mqtt_tls.sh 가 만든다 (infra/mosquitto/certs/ca.crt).
 */
public final class MqttTls {

    private MqttTls() {
    }

    /**
     * CA 파일 경로를 찾는다. 상대 경로면 현재 폴더에서 먼저 찾고, 없으면 한 단계 위(저장소 루트)에서 찾는다
     * (앱은 qms-app 폴더에서 실행되고, .env 의 경로는 저장소 루트 기준이라서).
     */
    static Path resolve(String caFile) {
        Path p = Path.of(caFile);
        if (p.isAbsolute() || Files.exists(p)) {
            return p;
        }
        Path parent = Path.of("..").resolve(p).normalize();
        return Files.exists(parent) ? parent : p;
    }

    public static SSLSocketFactory socketFactory(String caFile) {
        Path path = resolve(caFile);
        try (InputStream in = Files.newInputStream(path)) {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certs.isEmpty()) {
                throw new IllegalStateException("CA 인증서가 비어 있습니다: " + path);
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : certs) {
                ks.setCertificateEntry("qg-mqtt-ca-" + i++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx.getSocketFactory();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("MQTT TLS CA 인증서를 읽지 못함: " + path, e);
        }
    }
}
