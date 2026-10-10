package dev.darshan.spotme.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BoxHealthIndicatorTest {

    @Test
    void readsKilobyteLines() {
        Map<String, Long> values = BoxHealthIndicator.parseKb(List.of(
                "MemTotal:         484356 kB",
                "MemAvailable:     120000 kB",
                "Name:\tjava",
                "Threads:\t42",
                "VmRSS:\t  301234 kB"));

        assertThat(values).containsEntry("MemTotal", 484356L)
                .containsEntry("MemAvailable", 120000L)
                .containsEntry("VmRSS", 301234L)
                .doesNotContainKeys("Name", "Threads");
    }

    @Test
    void skipsBrokenLines() {
        assertThat(BoxHealthIndicator.parseKb(List.of("MemTotal: lots kB", "garbage", ""))).isEmpty();
    }
}
