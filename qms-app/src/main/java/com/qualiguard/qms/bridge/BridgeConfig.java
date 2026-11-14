package com.qualiguard.qms.bridge;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** application.yml 의 qg.mqtt / qg.bridge 설정을 BridgeProperties 레코드로 바인딩한다. */
@Configuration
@EnableConfigurationProperties({ BridgeProperties.Mqtt.class, BridgeProperties.Bridge.class })
public class BridgeConfig {
}
