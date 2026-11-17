package com.ddareungi.global.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Header formats and the startup checks of {@code api.deprecations[]}. */
class DeprecationPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @EnableConfigurationProperties(DeprecationProperties.class)
    static class PropertiesConfig {}

    private static DeprecationProperties.Entry entry(String deprecation, String sunset, String link) {
        return new DeprecationProperties.Entry("/api/v1/routes/plan", deprecation, sunset, link);
    }

    @Test void defaultIsEmpty() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DeprecationProperties.class).deprecations()).isEmpty();
        });
    }

    @Test void bindsEntries() {
        runner.withPropertyValues(
                "api.deprecations[0].pattern=/api/v1/routes/plan",
                "api.deprecations[0].deprecation=2023-06-30T23:59:59Z",
                "api.deprecations[0].sunset=2027-06-30T23:59:59Z",
                "api.deprecations[0].link=https://docs.example.com/migration",
                "api.deprecations[1].pattern=/api/v1/old/**",
                "api.deprecations[1].deprecation=2026-12-01T09:00:00+09:00",
                "api.deprecations[1].sunset=2027-01-01T00:00:00+09:00"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            List<DeprecationProperties.Entry> entries = context.getBean(DeprecationProperties.class).deprecations();
            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).deprecationHeader()).isEqualTo("@1688169599");
            assertThat(entries.get(0).sunsetHeader()).isEqualTo("Wed, 30 Jun 2027 23:59:59 GMT");
            assertThat(entries.get(0).linkHeader()).isEqualTo("<https://docs.example.com/migration>; rel=\"deprecation\"");
            assertThat(entries.get(1).linkHeader()).isNull();
            // Offsets are normalized to GMT; the day is two digits (IMF-fixdate).
            assertThat(entries.get(1).sunsetHeader()).isEqualTo("Thu, 31 Dec 2026 15:00:00 GMT");
            assertThat(entries.get(1).deprecationHeader()).isEqualTo("@1796083200");
        });
    }

    @Test void specExampleAndPaddedDay() {
        var entry = entry("2023-06-30T23:59:59Z", "2027-07-01T00:00:00Z", null);
        assertThat(entry.deprecationHeader()).isEqualTo("@1688169599");
        assertThat(entry.sunsetHeader()).isEqualTo("Thu, 01 Jul 2027 00:00:00 GMT");
        // Fractions are truncated to seconds.
        assertThat(entry("2023-06-30T23:59:59.900Z", "2027-07-01T00:00:00.5Z", null).deprecationHeader())
                .isEqualTo("@1688169599");
    }

    @Test void sunsetEqualToDeprecationIsAccepted() {
        new DeprecationProperties(List.of(entry("2027-01-01T00:00:00Z", "2027-01-01T09:00:00+09:00", null)));
    }

    @Test void sunsetBeforeDeprecationFailsStartup() {
        assertThatThrownBy(() -> new DeprecationProperties(List.of(
                entry("2027-01-01T00:00:01Z", "2027-01-01T00:00:00Z", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api.deprecations[0].sunset");
        runner.withPropertyValues(
                "api.deprecations[0].pattern=/api/v1/routes/plan",
                "api.deprecations[0].deprecation=2027-06-30T23:59:59Z",
                "api.deprecations[0].sunset=2023-06-30T23:59:59Z"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("must not be before deprecation");
        });
    }

    @Test void invalidEntriesFailStartup() {
        assertInvalid(new DeprecationProperties.Entry(null, "2023-06-30T23:59:59Z", "2027-06-30T23:59:59Z", null), "pattern");
        assertInvalid(new DeprecationProperties.Entry("api/v1/x", "2023-06-30T23:59:59Z", "2027-06-30T23:59:59Z", null), "pattern");
        assertInvalid(new DeprecationProperties.Entry("/api/{", "2023-06-30T23:59:59Z", "2027-06-30T23:59:59Z", null), "pattern");
        assertInvalid(entry(null, "2027-06-30T23:59:59Z", null), "deprecation");
        assertInvalid(entry("2023-06-30T23:59:59Z", null, null), "sunset");
        assertInvalid(entry("2023-06-30", "2027-06-30T23:59:59Z", null), "deprecation");
        assertInvalid(entry("2023-06-30T23:59:59", "2027-06-30T23:59:59Z", null), "deprecation"); // no offset
        assertInvalid(entry("2023-06-30T23:59:59Z", "Wed, 30 Jun 2027 23:59:59 GMT", null), "sunset");
        assertInvalid(entry("2023-06-30T23:59:59Z", "2027-06-30T23:59:59Z", "/docs/migration"), "link");
        assertInvalid(entry("2023-06-30T23:59:59Z", "2027-06-30T23:59:59Z", "javascript:alert(1)"), "link");
    }

    private static void assertInvalid(DeprecationProperties.Entry entry, String field) {
        assertThatThrownBy(() -> new DeprecationProperties(List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field);
    }
}
