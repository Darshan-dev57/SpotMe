package dev.darshan.spotme.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Adds the machine's memory to /actuator/health. The hosted box has under 500 MB of memory
 * and the face models live outside the Java heap, so the JVM's own numbers are not enough:
 * this reads what Linux reports in /proc. On other systems it reports UNKNOWN.
 */
@Component("box")
public class BoxHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        Map<String, Long> memory = readKb(Path.of("/proc/meminfo"));
        Map<String, Long> self = readKb(Path.of("/proc/self/status"));
        if (!memory.containsKey("MemTotal")) {
            return Health.unknown().build();
        }
        long swapTotal = memory.getOrDefault("SwapTotal", 0L);
        long swapFree = memory.getOrDefault("SwapFree", 0L);
        return Health.up()
                .withDetail("memoryTotalMb", memory.get("MemTotal") / 1024)
                .withDetail("memoryAvailableMb", memory.getOrDefault("MemAvailable", 0L) / 1024)
                .withDetail("swapUsedMb", (swapTotal - swapFree) / 1024)
                .withDetail("processRssMb", self.getOrDefault("VmRSS", 0L) / 1024)
                .build();
    }

    static Map<String, Long> readKb(Path file) {
        try {
            return parseKb(Files.readAllLines(file));
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** Reads lines like "MemAvailable:   123456 kB" into name -> kB. Other lines are skipped. */
    static Map<String, Long> parseKb(List<String> lines) {
        Map<String, Long> values = new HashMap<>();
        for (String line : lines) {
            String[] parts = line.split(":", 2);
            if (parts.length < 2 || !parts[1].trim().endsWith("kB")) {
                continue;
            }
            String number = parts[1].trim().split("\\s+")[0];
            try {
                values.put(parts[0].trim(), Long.parseLong(number));
            } catch (NumberFormatException e) {
                // not a number, skip it
            }
        }
        return values;
    }
}
