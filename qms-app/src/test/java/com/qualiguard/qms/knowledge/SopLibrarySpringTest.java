package com.qualiguard.qms.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 스프링이 SopLibrary 빈을 만들 수 있는지 확인한다.
 * 생성자가 둘인데 @Autowired 가 없으면 기본 생성자를 찾다가 NoSuchMethodException 으로 앱 기동이 실패한다.
 * 앱 전체 기동 시험(contextLoads)은 CI 에서 빠지므로 이 시험이 대신 잡는다.
 */
class SopLibrarySpringTest {

    @Test
    void springCreatesBeanWithDefaultDocuments() {
        try (var ctx = new AnnotationConfigApplicationContext(SopLibrary.class)) {
            SopLibrary sop = ctx.getBean(SopLibrary.class);
            assertThat(sop.size()).isPositive();
        }
    }
}
