package com.ddareungi.global.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class AccessLogFilterTest {
    final AccessLogFilter filter = new AccessLogFilter();

    @Test void logsMethodPathStatusAndElapsedWithoutQueryOrBody(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/routes/search");
        request.setQueryString("latitude=37.5551234&longitude=126.9881234");
        request.setContent("{\"origin\":{\"latitude\":37.5551234}}".getBytes());
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override protected void service(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                res.setStatus(201);
            }
        }));
        assertThat(output.getOut()).containsPattern("method=POST path=/api/v1/routes/search status=201 elapsedMs=\\d+");
        assertThat(output.getAll()).doesNotContain("37.5551234", "126.9881234", "latitude");
    }

    @Test void escapingExceptionIsLoggedAs500(CapturedOutput output) {
        var request = new MockHttpServletRequest("GET", "/api/v1/boom");
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);
        assertThat(output.getOut()).contains("method=GET path=/api/v1/boom status=500");
    }

    @Test void actuatorIsNotLogged(CapturedOutput output) throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(output.getAll()).doesNotContain("path=/actuator");
    }

    @Test void pathIsSanitizedAndBounded() {
        assertThat(AccessLogFilter.path("/a\nb\rc?x=1")).isEqualTo("/a?b?c");
        assertThat(AccessLogFilter.path(null)).isEmpty();
        assertThat(AccessLogFilter.path("/" + "x".repeat(400))).hasSize(AccessLogFilter.MAX_PATH_LENGTH + 3).endsWith("...");
    }
}
