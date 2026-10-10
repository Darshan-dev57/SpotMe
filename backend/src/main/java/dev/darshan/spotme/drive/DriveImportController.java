package dev.darshan.spotme.drive;

import dev.darshan.spotme.event.EventAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Organizer endpoints for importing a public Google Drive folder into an event. */
@RestController
@RequestMapping("/api/events/{eventId}/drive-imports")
public class DriveImportController {

    public record StartRequest(@NotBlank @Size(max = 500) String link) {
    }

    private final EventAccess access;
    private final DriveImportService imports;

    public DriveImportController(EventAccess access, DriveImportService imports) {
        this.access = access;
        this.imports = imports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> start(@PathVariable UUID eventId, @Valid @RequestBody StartRequest req, HttpServletRequest http) {
        access.requireOwner(eventId, http);
        access.requireLive(eventId);
        UUID id = imports.start(eventId, req.link());
        return imports.list(eventId).stream().filter(i -> id.equals(i.get("id"))).findFirst().orElseThrow();
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID eventId, HttpServletRequest http) {
        access.requireOwner(eventId, http);
        return imports.list(eventId);
    }
}
