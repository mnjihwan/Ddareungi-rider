package com.ddareungi.clientconfig;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.global.response.ApiErrorResponse.Detail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ClientConfigServiceTest {
    private static ClientConfigProperties properties(boolean maintenance, String message) {
        return new ClientConfigProperties(
                new ClientConfigProperties.Web(maintenance, message),
                new ClientConfigProperties.Platforms(
                        new ClientConfigProperties.Mobile("1.9.0", "1.10.0", "https://play.example/app"),
                        new ClientConfigProperties.Mobile("2.0.0", "2.1.0", null)),
                List.of("v1"));
    }

    private final ClientConfigService service = new ClientConfigService(properties(false, null));

    @ParameterizedTest(name = "android {0} -> force={1}, update={2}")
    @CsvSource({
            "0.0.0,  true,  true",
            "1.8.99, true,  true",
            "1.9.0,  false, true",   // == minimum: no forced update
            "1.9.9,  false, true",
            "1.10.0, false, false",  // == latest (1.10.0 > 1.9.x numerically)
            "1.10.1, false, false",
            "2.0.0,  false, false"
    })
    void versionDecisionBoundaries(String appVersion, boolean force, boolean update) {
        ClientConfigResponse response = service.resolve("android", appVersion);
        assertThat(response.forceUpdate()).isEqualTo(force);
        assertThat(response.updateAvailable()).isEqualTo(update);
        assertThat(response.platform()).isEqualTo("android");
        assertThat(response.minimumVersion()).isEqualTo("1.9.0");
        assertThat(response.latestVersion()).isEqualTo("1.10.0");
        assertThat(response.storeUrl()).isEqualTo("https://play.example/app");
        assertThat(response.supportedApiVersions()).containsExactly("v1");
        assertThat(response.maintenance()).isFalse();
        assertThat(response.maintenanceMessage()).isNull();
    }

    @Test void iosUsesItsOwnPolicy() {
        ClientConfigResponse response = service.resolve("ios", "1.10.0");
        assertThat(response.forceUpdate()).isTrue();
        assertThat(response.updateAvailable()).isTrue();
        assertThat(response.minimumVersion()).isEqualTo("2.0.0");
        assertThat(response.latestVersion()).isEqualTo("2.1.0");
        assertThat(response.storeUrl()).isNull();
    }

    @Test void webHasNoVersionPolicyAndIgnoresAppVersion() {
        for (String appVersion : new String[]{null, "", "garbage", "0.0.1"}) {
            ClientConfigResponse response = service.resolve("web", appVersion);
            assertThat(response).isEqualTo(new ClientConfigResponse("web", null, null, false, false, false, null,
                    List.of("v1"), null));
        }
    }

    @Test void maintenanceIsReportedToEveryPlatform() {
        var maintenance = new ClientConfigService(properties(true, "새벽 점검 중입니다."));
        for (String platform : new String[]{"android", "ios", "web"}) {
            ClientConfigResponse response = maintenance.resolve(platform, "1.0.0");
            assertThat(response.maintenance()).isTrue();
            assertThat(response.maintenanceMessage()).isEqualTo("새벽 점검 중입니다.");
        }
        var defaultMessage = new ClientConfigService(properties(true, null));
        assertThat(defaultMessage.resolve("web", null).maintenanceMessage())
                .isEqualTo(ErrorCode.MAINTENANCE_MODE.defaultMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ANDROID", "Android", "windows", "android ", "web,android"})
    void unknownPlatformIs400(String platform) {
        assertInvalid(() -> service.resolve(platform, "1.0.0"), "platform");
    }

    @Test void missingPlatformIs400() {
        assertInvalid(() -> service.resolve(null, "1.0.0"), "platform");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1.4", "1.4.0.0", "1.4.0-rc1", "01.0.0", "abc"})
    void invalidMobileVersionIs400(String appVersion) {
        assertInvalid(() -> service.resolve("android", appVersion), "appVersion");
        assertInvalid(() -> service.resolve("ios", appVersion), "appVersion");
    }

    @Test void missingMobileVersionIs400() {
        assertInvalid(() -> service.resolve("android", null), "appVersion");
        assertInvalid(() -> service.resolve("ios", null), "appVersion");
    }

    private static void assertInvalid(Runnable call, String field) {
        ApiException exception = catchThrowableOfType(ApiException.class, call::run);
        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(exception.details()).extracting(Detail::field).containsExactly(field);
    }
}
