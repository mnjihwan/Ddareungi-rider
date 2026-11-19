package com.ddareungi.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfraDataSourceGuardTest {
    private static MockEnvironment withPassword(String value) {
        return new MockEnvironment().withProperty(InfraDataSourceGuard.PROPERTY, value);
    }

    @Test void unresolvedEnvironmentVariableFailsTheStartup() {
        // application-infra.yml: password: ${MYSQL_PASSWORD}; the variable is not set.
        assertThatThrownBy(() -> new InfraDataSourceGuard(withPassword("${MYSQL_PASSWORD}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MYSQL_PASSWORD");
    }

    @Test void missingOrBlankPasswordFailsTheStartup() {
        assertThatThrownBy(() -> new InfraDataSourceGuard(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InfraDataSourceGuard(withPassword("  ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void configuredPasswordPassesWithoutBeingLogged() {
        assertThatCode(() -> new InfraDataSourceGuard(withPassword("secret-value"))).doesNotThrowAnyException();
    }

    @Test void placeholderResolvedFromAnotherPropertyPasses() {
        var environment = withPassword("${MYSQL_PASSWORD}").withProperty("MYSQL_PASSWORD", "secret-value");
        assertThatCode(() -> new InfraDataSourceGuard(environment)).doesNotThrowAnyException();
    }
}
