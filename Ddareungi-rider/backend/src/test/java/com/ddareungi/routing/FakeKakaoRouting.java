package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * In-memory Kakao Mobility directions stub for {@link KakaoRouteClient} (A6 tests): answers every
 * {@code /v2/routing/walk|bicycle} call with a straight two-point route whose distance is the straight-line distance
 * × 1.3 and whose time follows a fixed speed (walk 1.2 m/s, bicycle 4 m/s). Counts calls, can delay every answer and
 * can fail selected calls with a status (429 is not retried by the resilience layer).
 */
final class FakeKakaoRouting implements ClientHttpRequestFactory {
    final AtomicInteger calls = new AtomicInteger();
    final List<URI> uris = new CopyOnWriteArrayList<>();
    volatile long delayMillis;
    volatile Predicate<URI> failing = uri -> false;
    volatile HttpStatus failure = HttpStatus.TOO_MANY_REQUESTS;

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        return new MockClientHttpRequest(method, uri) {
            @Override
            protected org.springframework.http.client.ClientHttpResponse executeInternal() throws IOException {
                calls.incrementAndGet();
                uris.add(uri);
                if (delayMillis > 0) {
                    try { Thread.sleep(delayMillis); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
                if (failing.test(uri)) return new MockClientHttpResponse(new byte[0], failure);
                var response = new MockClientHttpResponse(body(uri).getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return response;
            }
        };
    }

    long count(String fragment) { return uris.stream().filter(uri -> uri.toString().contains(fragment)).count(); }

    static boolean between(URI uri, Location from, Location to) {
        var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        return Double.parseDouble(query.getFirst("start_y")) == from.latitude()
                && Double.parseDouble(query.getFirst("start_x")) == from.longitude()
                && Double.parseDouble(query.getFirst("end_y")) == to.latitude()
                && Double.parseDouble(query.getFirst("end_x")) == to.longitude();
    }

    static double distance(Location from, Location to) { return from.distanceTo(to) * 1.3; }
    static double seconds(Location from, Location to, boolean walk) { return distance(from, to) / (walk ? 1.2 : 4.0); }

    private static String body(URI uri) {
        var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        var from = new Location(Double.parseDouble(query.getFirst("start_y")), Double.parseDouble(query.getFirst("start_x")));
        var to = new Location(Double.parseDouble(query.getFirst("end_y")), Double.parseDouble(query.getFirst("end_x")));
        boolean walk = uri.getPath().endsWith("/walk");
        double distance = distance(from, to);
        double time = seconds(from, to, walk);
        return String.format(Locale.ROOT, """
                {"status":"OK","route":{"properties":{"totalDistance":%.3f,"totalTime":%.3f},"legs":[{
                "steps":[{"properties":{"guidance":"직진","distance":%.3f,"time":%.3f},
                "path":{"points":[[%s,%s],[%s,%s]]}}]}]}}""", distance, time, distance, time,
                from.longitude(), from.latitude(), to.longitude(), to.latitude());
    }
}
