package dev.darshan.spotme.auth;

import dev.darshan.spotme.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Reads "Authorization: Bearer ..." and stores the caller on the request.
 * /api/events/** needs an organizer, /api/attendee/** needs an attendee.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL = "spotme.principal";

    private final SessionService sessions;

    public AuthInterceptor(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) {
            return true; // CORS preflight carries no token
        }
        String path = request.getRequestURI();
        String token = bearer(request);
        Principal principal = token == null ? null : sessions.resolve(token).orElse(null);

        boolean organizerPath = path.startsWith("/api/events") || path.equals("/api/auth/me") || path.equals("/api/auth/logout");
        boolean attendeePath = path.startsWith("/api/attendee");

        if (organizerPath && !(principal instanceof Principal.Organizer)) {
            throw ApiException.unauthorized();
        }
        if (attendeePath && !(principal instanceof Principal.Attendee)) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "access_ended",
                    "Your access to this event has ended. Open your invite link again.");
        }
        request.setAttribute(PRINCIPAL, principal);
        return true;
    }

    static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    public static Principal.Organizer organizer(HttpServletRequest request) {
        return (Principal.Organizer) request.getAttribute(PRINCIPAL);
    }

    public static Principal.Attendee attendee(HttpServletRequest request) {
        return (Principal.Attendee) request.getAttribute(PRINCIPAL);
    }
}
