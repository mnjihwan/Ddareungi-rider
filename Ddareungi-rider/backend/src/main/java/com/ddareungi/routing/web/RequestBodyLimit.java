package com.ddareungi.routing.web;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * 16 KiB request body limit for JSON POSTs under {@code /api/v1} (spec 13.1 "요청 JSON 크기 상한", phase 3 decision 9,
 * 413 {@code PAYLOAD_TOO_LARGE}).
 * <ul>
 *   <li>A declared {@code Content-Length} above the limit is refused by {@link Interceptor} before the body is read.</li>
 *   <li>A body without a length (chunked) is read through a limited stream ({@link Advice}); going past the limit
 *       raises {@link PayloadTooLargeException}, which the JSON converter wraps in an
 *       {@code HttpMessageNotReadableException} that {@code GlobalExceptionHandler} answers with 413.</li>
 * </ul>
 * Kept in the routing module because the route search introduced the limit (A6); it applies to every
 * {@code @RequestBody} under {@code /api/v1}.
 */
@Configuration(proxyBeanMethods = false)
public class RequestBodyLimit implements WebMvcConfigurer {
    public static final int MAX_BYTES = 16 * 1024;
    static final String API_PREFIX = "/api/v1/";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Interceptor()).addPathPatterns("/api/v1/**");
    }

    /** Thrown by the limited stream; found in the cause chain of the converter's exception. */
    public static final class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException() { super("request body exceeds " + MAX_BYTES + " bytes"); }
    }

    static final class Interceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if ("POST".equalsIgnoreCase(request.getMethod()) && request.getContentLengthLong() > MAX_BYTES)
                throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
            return true;
        }
    }

    @ControllerAdvice
    public static class Advice extends RequestBodyAdviceAdapter {
        @Override
        public boolean supports(MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
            return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                    && attributes.getRequest().getRequestURI().startsWith(API_PREFIX);
        }

        @Override
        public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type targetType,
                                               Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
            InputStream body = new LimitedInputStream(message.getBody(), MAX_BYTES);
            HttpHeaders headers = message.getHeaders();
            return new HttpInputMessage() {
                @Override public InputStream getBody() { return body; }
                @Override public HttpHeaders getHeaders() { return headers; }
            };
        }
    }

    /** Fails once more than {@code limit} bytes have been read. */
    static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count(1);
            return value;
        }

        @Override
        public int read(byte @Nullable [] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) count(read);
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) count(skipped);
            return skipped;
        }

        @Override public boolean markSupported() { return false; }

        private void count(long read) throws PayloadTooLargeException {
            count += read;
            if (count > limit) throw new PayloadTooLargeException();
        }
    }
}
