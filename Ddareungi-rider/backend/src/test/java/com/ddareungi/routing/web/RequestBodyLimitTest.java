package com.ddareungi.routing.web;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A6 body limit pieces: the limited stream used for bodies without Content-Length, and the Content-Length check. */
class RequestBodyLimitTest {
    @Test void streamAllowsExactlyTheLimitAndFailsOneBytePast() throws Exception {
        var exact = new RequestBodyLimit.LimitedInputStream(new ByteArrayInputStream(new byte[16384]), 16384);
        assertThat(exact.readAllBytes()).hasSize(16384);
        var over = new RequestBodyLimit.LimitedInputStream(new ByteArrayInputStream(new byte[16385]), 16384);
        assertThatThrownBy(over::readAllBytes).isInstanceOf(RequestBodyLimit.PayloadTooLargeException.class);
        var single = new RequestBodyLimit.LimitedInputStream(new ByteArrayInputStream(new byte[3]), 2);
        single.read();
        single.read();
        assertThatThrownBy(single::read).isInstanceOf(RequestBodyLimit.PayloadTooLargeException.class);
    }

    @Test void declaredLengthAboveTheLimitIsRefusedBeforeReading() {
        var interceptor = new RequestBodyLimit.Interceptor();
        var request = new MockHttpServletRequest("POST", "/api/v1/routes/search");
        request.setContent(new byte[16385]);
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        request.setContent(new byte[16384]);
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        var get = new MockHttpServletRequest("GET", "/api/v1/stations/nearby");
        get.setContent(new byte[20000]);
        assertThat(interceptor.preHandle(get, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
