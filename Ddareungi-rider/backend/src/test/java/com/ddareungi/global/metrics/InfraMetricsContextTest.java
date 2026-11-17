package com.ddareungi.global.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Infra profile wiring (same context configuration as {@code InfraContextTest}, so the cached context is reused).
 * No MySQL runs here, so the catalog never loads and its gauges read NaN instead of failing the scrape.
 */
@ActiveProfiles("infra")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "seoul-bike.api.key=local-context-test", "seoul-bike.api.collection-enabled=false"
})
class InfraMetricsContextTest {
    @Autowired MeterRegistry registry;
    @Autowired Environment environment;

    @Test void catalogAndRealtimeGaugesAreRegisteredAndReadNaNWithoutACatalog() {
        assertThat(registry.find(StationCatalogMetrics.SIZE).gauge()).isNotNull();
        assertThat(registry.find(StationCatalogMetrics.SIZE).gauge().value()).isNaN();
        assertThat(registry.find(StationCatalogMetrics.AGE).gauge().value()).isNaN();
        assertThat(registry.find(RealtimeMetrics.STATIONS).tag("status", "fresh").gauge().value()).isNaN();
        assertThat(registry.find(ProviderCircuitMetrics.NAME).gauges()).hasSize(9);
        assertThat(registry.find(ProviderCallMetrics.NAME).timers()).hasSize(3 * ProviderCallOutcome.values().length);
    }

    @Test void infraLogsAreStructuredEcs() {
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(environment.getProperty("management.metrics.tags.application")).isEqualTo("ddareungrider");
    }
}
