package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** Startup validation of {@code realtime.freshness.*} (spec 11.0: the backend checks fresh &lt; max-stale). */
class RealtimeFreshnessPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @EnableConfigurationProperties(RealtimeFreshnessProperties.class)
    @Import(RealtimeFreshnessConfiguration.class)
    static class Config {}

    @Test
    void defaultsAre120And600() {
        runner.run(context -> {
            var policy = context.getBean(RealtimeFreshnessPolicy.class);
            assertThat(policy.freshSeconds()).isEqualTo(120);
            assertThat(policy.maxStaleSeconds()).isEqualTo(600);
        });
    }

    @Test
    void valuesCanBeOverridden() {
        runner.withPropertyValues("realtime.freshness.fresh-seconds=90", "realtime.freshness.max-stale-seconds=450")
                .run(context -> {
                    var policy = context.getBean(RealtimeFreshnessPolicy.class);
                    assertThat(policy.freshSeconds()).isEqualTo(90);
                    assertThat(policy.maxStaleSeconds()).isEqualTo(450);
                });
    }

    @Test
    void freshNotBelowMaxStaleFailsStartup() {
        runner.withPropertyValues("realtime.freshness.fresh-seconds=600", "realtime.freshness.max-stale-seconds=600")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("realtime.freshness.fresh-seconds=700")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nonPositiveFreshFailsStartup() {
        runner.withPropertyValues("realtime.freshness.fresh-seconds=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
