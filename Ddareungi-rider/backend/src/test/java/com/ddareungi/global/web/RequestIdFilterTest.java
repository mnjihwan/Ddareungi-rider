package com.ddareungi.global.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {
    final RequestIdFilter filter = new RequestIdFilter();

    @ParameterizedTest
    @ValueSource(strings = {"abc-123_XYZ", "a", "0123456789012345678901234567890123456789012345678901234567890123"})
    void reusesValidClientId(String id) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/status");
        request.addHeader(RequestIdFilter.HEADER, id);
        var response = new MockHttpServletResponse();
        var seenInMdc = new AtomicReference<String>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInMdc.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo(id);
        assertThat(seenInMdc.get()).isEqualTo(id);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "slash/inside", "한글", "semi;colon",
            "01234567890123456789012345678901234567890123456789012345678901234"})
    void replacesInvalidClientIdWithUuid(String id) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/status");
        request.addHeader(RequestIdFilter.HEADER, id);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        String generated = response.getHeader(RequestIdFilter.HEADER);
        assertThat(generated).isNotEqualTo(id);
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo(generated);
    }

    @Test void generatesIdWhenHeaderMissing() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), response, new MockFilterChain());
        assertThat(UUID.fromString(response.getHeader(RequestIdFilter.HEADER))).isNotNull();
    }

    @Test void setsHeaderBeforeChainAndClearsMdcEvenWhenChainFails() {
        var request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(RequestIdFilter.HEADER, "trace-1");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, new MockFilterChain() {
            @Override public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        })).isInstanceOf(IllegalStateException.class);
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("trace-1");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
