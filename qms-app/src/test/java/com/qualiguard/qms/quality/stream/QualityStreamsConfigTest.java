package com.qualiguard.qms.quality.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QualityStreamsConfigTest {

    @Test
    void 다변량_감시_설정은_off_와_shadow_만_받는다() {
        assertThat(QualityStreamsConfig.shadow("shadow")).isTrue();
        assertThat(QualityStreamsConfig.shadow(" Shadow ")).isTrue();
        assertThat(QualityStreamsConfig.shadow("off")).isFalse();
        assertThat(QualityStreamsConfig.shadow("false")).isFalse();   // YAML 의 따옴표 없는 off
        assertThat(QualityStreamsConfig.shadow(null)).isFalse();
        assertThatThrownBy(() -> QualityStreamsConfig.shadow("on")).isInstanceOf(IllegalArgumentException.class);
    }
}
