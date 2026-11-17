package com.ddareungi.clientconfig;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.global.response.ApiErrorResponse.Detail;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Version decision of spec 6: {@code forceUpdate = appVersion < minimumVersion},
 * {@code updateAvailable = appVersion < latestVersion} (true whenever forceUpdate is). {@code buildNumber} is not
 * used. The maintenance flag is reported to every platform, including while maintenance is on.
 */
@Service
public class ClientConfigService {
    static final String PLATFORM_REASON = "android, ios, web 중 하나여야 합니다.";
    static final String VERSION_REASON = "major.minor.patch 형식의 버전이어야 합니다.";

    private final ClientConfigProperties properties;

    public ClientConfigService(ClientConfigProperties properties) {
        this.properties = properties;
    }

    public ClientConfigResponse resolve(@Nullable String platformValue, @Nullable String appVersionValue) {
        ClientPlatform platform = ClientPlatform.from(platformValue);
        if (platform == null) throw invalid("platform", PLATFORM_REASON);
        boolean maintenance = properties.web().maintenance();
        String message = maintenance ? maintenanceMessage(properties) : null;
        List<String> apiVersions = properties.supportedApiVersions();
        if (!platform.mobile()) {
            return new ClientConfigResponse(platform.value(), null, null, false, false, maintenance, message,
                    apiVersions, null);
        }
        AppVersion appVersion = AppVersion.tryParse(appVersionValue)
                .orElseThrow(() -> invalid("appVersion", VERSION_REASON));
        ClientConfigProperties.Mobile policy = properties.platforms().of(platform);
        boolean forceUpdate = appVersion.isBefore(policy.minimum());
        boolean updateAvailable = forceUpdate || appVersion.isBefore(policy.latest());
        return new ClientConfigResponse(platform.value(), policy.minimum().toString(), policy.latest().toString(),
                forceUpdate, updateAvailable, maintenance, message, apiVersions, policy.storeUrl());
    }

    /** The configured maintenance text, or the default message of {@code MAINTENANCE_MODE}. */
    public static String maintenanceMessage(ClientConfigProperties properties) {
        String configured = properties.web().maintenanceMessage();
        return configured == null ? ErrorCode.MAINTENANCE_MODE.defaultMessage() : configured;
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(ErrorCode.INVALID_PARAMETER, null, List.of(new Detail(field, reason)));
    }
}
