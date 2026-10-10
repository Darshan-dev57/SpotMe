package dev.darshan.spotme.eval;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.event.EventAccess;
import dev.darshan.spotme.search.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Accuracy harness only (eval profile). Returns raw distances for a query so the
 * threshold can be chosen from data. Disabled in normal runs: it answers 404.
 */
@RestController
public class EvalController {

    private final SearchService search;
    private final EventAccess access;
    private final boolean enabled;

    public EvalController(SearchService search, EventAccess access, SpotMeProperties props) {
        this.search = search;
        this.access = access;
        this.enabled = props.eval().enabled();
    }

    @PostMapping(value = "/api/events/{eventId}/eval-search", consumes = {"image/jpeg", "image/png"})
    public Map<String, Object> evalSearch(@PathVariable UUID eventId, @RequestBody byte[] selfie,
                                          HttpServletRequest http) {
        if (!enabled) {
            throw ApiException.notFound("Endpoint");
        }
        access.requireOwner(eventId, http);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            long t0 = System.nanoTime();
            float[] vector = search.selfieVector(selfie, new long[2]);
            long t1 = System.nanoTime();
            List<SearchService.Match> matches = search.search(eventId, UUID.randomUUID(), vector, 2.0, 1000);
            long t2 = System.nanoTime();
            out.put("status", "ok");
            out.put("matches", matches);
            out.put("inferenceMs", (t1 - t0) / 1e6);
            out.put("sqlMs", (t2 - t1) / 1e6);
        } catch (SearchService.BadSelfieException e) {
            out.put("status", e.code());
        }
        return out;
    }
}
