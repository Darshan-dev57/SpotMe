package dev.darshan.spotme.demo;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.common.RateLimiter;
import dev.darshan.spotme.config.SpotMeProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public endpoints for the demo event. Both answer 404 when the demo is switched off. */
@RestController
public class DemoController {

    private final DemoService demo;
    private final RateLimiter<String> invitesPerIp;

    public DemoController(DemoService demo, SpotMeProperties props) {
        this.demo = demo;
        this.invitesPerIp = new RateLimiter<>(Math.max(1, props.demo().invitesPerMinute()));
    }

    @GetMapping("/api/demo")
    public Map<String, Object> summary() {
        requireEnabled();
        return demo.summary();
    }

    @PostMapping("/api/demo/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> invite(HttpServletRequest http) {
        requireEnabled();
        if (!invitesPerIp.tryAcquire(clientIp(http))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many demo invites. Try again in a minute.");
        }
        return Map.of("token", demo.newVisitorInvite());
    }

    private void requireEnabled() {
        if (!demo.enabled()) {
            throw ApiException.notFound("Demo");
        }
    }

    /**
     * The visitor's address. Behind a proxy, Tomcat (server.forward-headers-strategy=native) reads
     * X-Forwarded-For from the right and stops at the first address that is not a trusted proxy, so
     * entries a client writes into the header itself are ignored.
     */
    private static String clientIp(HttpServletRequest http) {
        return http.getRemoteAddr();
    }
}
