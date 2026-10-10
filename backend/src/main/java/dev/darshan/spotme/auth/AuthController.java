package dev.darshan.spotme.auth;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.common.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Organizer accounts: register, login, logout, me. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(@NotBlank @Email String email,
                                  @NotBlank @Size(max = 60) String name,
                                  @NotBlank @Size(min = 8, max = 100) String password) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record SessionResponse(String token, UUID organizerId, String name) {
    }

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    // slows down password guessing: 10 login attempts per email per minute
    private final RateLimiter<String> loginLimiter = new RateLimiter<>(10);

    public AuthController(JdbcTemplate jdbc, SessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse register(@Valid @RequestBody RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM organizers WHERE email = ?", Integer.class, email);
        if (existing != null && existing > 0) {
            throw ApiException.conflict("email_taken", "An account with this email already exists.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizers (id, email, display_name, password_hash) VALUES (?, ?, ?, ?)",
                id, email, req.name().trim(), encoder.encode(req.password()));
        return new SessionResponse(sessions.startOrganizerSession(id), id, req.name().trim());
    }

    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest req) {
        String email = req.email().trim().toLowerCase();
        if (!loginLimiter.tryAcquire(email)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "slow_down", "Too many attempts. Wait a minute and try again.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, display_name, password_hash FROM organizers WHERE email = ?", email);
        // same message for "no such user" and "wrong password"
        if (rows.isEmpty() || !encoder.matches(req.password(), (String) rows.get(0).get("password_hash"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials", "Email or password is wrong.");
        }
        UUID id = (UUID) rows.get(0).get("id");
        return new SessionResponse(sessions.startOrganizerSession(id), id, (String) rows.get(0).get("display_name"));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        sessions.end(AuthInterceptor.bearer(request));
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        UUID id = AuthInterceptor.organizer(request).organizerId();
        return jdbc.queryForMap("SELECT id, email, display_name AS name FROM organizers WHERE id = ?", id);
    }
}
