#!/bin/bash
# qms-app(Spring Boot 4) 뼈대를 Spring Initializr에서 받아 오고,
# MQTT 의존성과 메모리 제한을 추가한다. 이미 만든 브리지 소스(src/.../bridge)는 그대로 둔다.
set -euo pipefail
cd "$(dirname "$0")"
if [ -f qms-app/build.gradle ]; then
  echo "qms-app/build.gradle 이 이미 있습니다. 다시 만들려면 build.gradle 을 지우고 실행하세요."; exit 0
fi
echo "1) Spring Initializr에서 프로젝트 뼈대 내려받기"
curl -fsSL https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d javaVersion=21 \
  -d groupId=com.qualiguard -d artifactId=qms-app -d name=qms-app \
  -d description="QualiGuard QMS" -d packageName=com.qualiguard.qms \
  -d baseDir=qms-app -d dependencies=web,integration,kafka,validation,actuator \
  -o /tmp/qms-app.zip
unzip -o -q /tmp/qms-app.zip -d . && rm -f /tmp/qms-app.zip
rm -f qms-app/src/main/resources/application.properties   # 설정은 application.yml 사용

echo "2) MQTT 의존성 추가 (Spring Integration MQTT + Eclipse Paho MQTT v5 클라이언트)"
perl -0pi -e "s/dependencies \{\n/dependencies {\n\timplementation 'org.springframework.integration:spring-integration-mqtt'\n\timplementation 'org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5'\n/" qms-app/build.gradle

echo "3) 개발 실행 시 JVM 메모리 제한 (8GB RAM 환경)"
cat >> qms-app/build.gradle <<'G'

// 8GB RAM 환경: 개발 중 bootRun의 힙을 384MB로 제한
tasks.named('bootRun') {
	jvmArgs = ['-Xms128m', '-Xmx384m']
}
G
echo "완료. Spring Boot 버전:"
grep -m1 "org.springframework.boot'" qms-app/build.gradle
