package com.ddareungi.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** One engine selected by routing.provider backs both segment providers; the local profile (routing.enabled=false) still starts. */
class RoutingProviderWiringTest {
    @Nested
    @ActiveProfiles("local")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    class DefaultKakao {
        @Autowired RouteProvider engine;
        @Autowired WalkingRouteProvider walking;
        @Autowired BicycleRouteProvider bicycle;
        @Autowired PlanBikeRoute planner;
        @Test void kakaoEngineBacksBothSegmentsAndLocalProfileDisablesPlanning() {
            assertThat(engine).isInstanceOf(KakaoRouteClient.class);
            assertThat(walking).isInstanceOf(EngineWalkingRouteProvider.class);
            assertThat(bicycle).isInstanceOf(EngineBicycleRouteProvider.class);
            assertThat(walking.attribution()).isEqualTo(bicycle.attribution()).isEqualTo("경로 제공 · Kakao Maps");
            assertThat(planner.configured()).isFalse();
        }
    }

    @Nested
    @ActiveProfiles("local")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {"routing.provider=ors", "routing.ors.api-key=test-key"})
    class Ors {
        @Autowired RouteProvider engine;
        @Autowired WalkingRouteProvider walking;
        @Autowired BicycleRouteProvider bicycle;
        @Autowired PlanBikeRoute planner;
        @Test void orsEngineBacksBothSegments() {
            assertThat(engine).isInstanceOf(OpenRouteServiceClient.class);
            assertThat(walking.configured()).isTrue();
            assertThat(bicycle.configured()).isTrue();
            assertThat(bicycle.attribution()).startsWith("openrouteservice");
            assertThat(planner.configured()).isFalse(); // routing.enabled=false in the local profile
        }
    }
}
