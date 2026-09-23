package com.city.service.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class AmapTravelTimeServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("amap.web-service-key=test-key")
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldCreateServiceThroughSpringConstructorInjection() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AmapTravelTimeService.class);
            assertThat(context.getBean(AmapTravelTimeService.class)).isNotNull();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AmapTravelTimeService.class)
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
