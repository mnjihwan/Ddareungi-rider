package com.ddareungi.global.web;

import com.ddareungi.global.ratelimit.RateLimitInterceptor;
import com.ddareungi.routing.web.RequestBodyLimit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review L11: the API interceptors run in the documented {@link InterceptorOrder} on the real handler chain
 * (deprecation → maintenance → body limit → rate limit), independent of the order the configurers are applied in.
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "management.server.port=-1",
        // application.yml already deprecates /api/v1/routes/plan; set explicitly so the test does not depend on it.
        "api.deprecations[0].pattern=/api/v1/routes/plan",
        "api.deprecations[0].deprecation=2026-11-17T00:00:00Z",
        "api.deprecations[0].sunset=2027-06-30T23:59:59Z"
})
class InterceptorOrderTest {
    private static final List<Class<? extends HandlerInterceptor>> API_INTERCEPTORS = List.of(
            DeprecationInterceptor.class, MaintenanceInterceptor.class, RequestBodyLimit.Interceptor.class,
            RateLimitInterceptor.class);

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    @Test void constantsDocumentTheIntendedOrder() {
        assertThat(List.of(InterceptorOrder.DEPRECATION, InterceptorOrder.MAINTENANCE,
                InterceptorOrder.REQUEST_BODY_LIMIT, InterceptorOrder.RATE_LIMIT)).isSorted().doesNotHaveDuplicates();
        assertThat(DeprecationInterceptor.ORDER).isEqualTo(InterceptorOrder.DEPRECATION);
        assertThat(MaintenanceInterceptor.ORDER).isEqualTo(InterceptorOrder.MAINTENANCE);
    }

    @Test void deprecatedRoutePostRunsAllFourInTheDocumentedOrder() throws Exception {
        assertThat(apiInterceptors(chain("POST", "/api/v1/routes/plan"))).containsExactlyElementsOf(API_INTERCEPTORS);
    }

    @Test void otherApiPathsKeepTheSameRelativeOrder() throws Exception {
        assertThat(apiInterceptors(chain("POST", "/api/v1/routes/search")))
                .containsExactly(MaintenanceInterceptor.class, RequestBodyLimit.Interceptor.class, RateLimitInterceptor.class);
        // Path parameter variant (review L1): matched on the parsed path, so the same interceptors apply.
        assertThat(apiInterceptors(chain("POST", "/api/v1;x=1/routes/search")))
                .containsExactly(MaintenanceInterceptor.class, RequestBodyLimit.Interceptor.class, RateLimitInterceptor.class);
    }

    private HandlerExecutionChain chain(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        var chain = mapping.getHandler(request);
        assertThat(chain).as("handler for %s %s", method, path).isNotNull();
        return chain;
    }

    private static List<Class<?>> apiInterceptors(HandlerExecutionChain chain) {
        return chain.getInterceptorList().stream().<Class<?>>map(Object::getClass)
                .filter(API_INTERCEPTORS::contains).toList();
    }
}
