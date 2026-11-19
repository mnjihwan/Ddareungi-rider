package com.ddareungi.global.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails the infra startup when the MySQL password is missing.
 *
 * <p>{@code application-infra.yml} has no default for {@code MYSQL_PASSWORD}, but Spring Boot binds an unresolved
 * {@code ${MYSQL_PASSWORD}} as the literal text, so without this check the application would start and only fail
 * later at the first connection (observed on 2026-11-19). The guard turns a missing or blank value into a clear
 * startup error. The value itself is never logged.
 */
@Profile("infra")
@Component
public class InfraDataSourceGuard {
    static final String PROPERTY = "spring.datasource.password";

    public InfraDataSourceGuard(Environment environment) {
        String password;
        try {
            password = environment.getProperty(PROPERTY);
        } catch (IllegalArgumentException unresolved) {
            // Unresolvable placeholder: the environment variable is not set.
            throw missing();
        }
        if (password == null || password.isBlank()) throw missing();
    }

    private static IllegalStateException missing() {
        return new IllegalStateException("MYSQL_PASSWORD must be set for the infra profile (" + PROPERTY + ").");
    }
}
