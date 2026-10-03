package dev.darshan.spotme.auth;

import java.util.UUID;

/** Who is calling. Resolved from the bearer token by {@link AuthInterceptor}. */
public sealed interface Principal {

    record Organizer(UUID organizerId) implements Principal {
    }

    /** An invited attendee, always tied to exactly one event. */
    record Attendee(UUID membershipId, UUID eventId) implements Principal {
    }
}
