package com.ddareungi.clientconfig;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Binding, defaults and the startup checks of {@link ClientConfigProperties}. */
class ClientConfigPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @EnableConfigurationProperties(ClientConfigProperties.class)
    static class PropertiesConfig {}

    @Test void defaultsWithoutAnyProperty() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ClientConfigProperties properties = context.getBean(ClientConfigProperties.class);
            assertThat(properties.web().maintenance()).isFalse();
            assertThat(properties.web().maintenanceMessage()).isNull();
            assertThat(properties.supportedApiVersions()).containsExactly("v1");
            for (ClientConfigProperties.Mobile mobile : new ClientConfigProperties.Mobile[]{
                    properties.platforms().android(), properties.platforms().ios()}) {
                assertThat(mobile.minimumVersion()).isEqualTo("0.1.0");
                assertThat(mobile.latestVersion()).isEqualTo("0.1.0");
                assertThat(mobile.storeUrl()).isNull();
            }
        });
    }

    @Test void bindsEveryKey() {
        runner.withPropertyValues(
                "client.web.maintenance=true",
                "client.web.maintenance-message=  점검 안내  ",
                "client.platforms.android.minimum-version=1.3.0",
                "client.platforms.android.latest-version=1.10.0",
                "client.platforms.android.store-url=https://play.google.com/store/apps/details?id=com.ddareungi.rider",
                "client.platforms.ios.minimum-version=2.0.0",
                "client.platforms.ios.latest-version=2.0.0",
                "client.supported-api-versions=v1,v2"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            ClientConfigProperties properties = context.getBean(ClientConfigProperties.class);
            assertThat(properties.web().maintenance()).isTrue();
            assertThat(properties.web().maintenanceMessage()).isEqualTo("점검 안내");
            assertThat(properties.platforms().android().minimum()).isEqualTo(new AppVersion(1, 3, 0));
            assertThat(properties.platforms().android().latest()).isEqualTo(new AppVersion(1, 10, 0));
            assertThat(properties.platforms().android().storeUrl()).startsWith("https://play.google.com/");
            assertThat(properties.platforms().ios().minimumVersion()).isEqualTo("2.0.0");
            assertThat(properties.supportedApiVersions()).containsExactly("v1", "v2");
        });
    }

    @Test void blankMessageMeansNone() {
        runner.withPropertyValues("client.web.maintenance-message=   ").run(context ->
                assertThat(context.getBean(ClientConfigProperties.class).web().maintenanceMessage()).isNull());
    }

    @Test void minimumGreaterThanLatestFailsStartup() {
        // Numeric comparison: 1.10.0 > 1.9.0, so this is invalid although "1.10.0" < "1.9.0" as text.
        runner.withPropertyValues("client.platforms.android.minimum-version=1.10.0",
                "client.platforms.android.latest-version=1.9.0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("client.platforms.android.minimum-version");
        });
    }

    @Test void equalMinimumAndLatestIsValid() {
        runner.withPropertyValues("client.platforms.ios.minimum-version=1.9.0",
                "client.platforms.ios.latest-version=1.9.0").run(context -> assertThat(context).hasNotFailed());
    }

    @Test void invalidVersionFormatFailsStartup() {
        runner.withPropertyValues("client.platforms.ios.latest-version=1.4").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("client.platforms.ios.latest-version");
        });
        runner.withPropertyValues("client.platforms.android.minimum-version=1.4.0-beta").run(context ->
                assertThat(context).hasFailed());
    }

    @Test void invalidStoreUrlFailsStartup() {
        runner.withPropertyValues("client.platforms.android.store-url=market://details?id=x").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("store-url");
        });
    }

    @Test void invalidApiVersionsFailStartup() {
        runner.withPropertyValues("client.supported-api-versions=1").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("client.supported-api-versions=").run(context -> assertThat(context).hasFailed());
    }
}
