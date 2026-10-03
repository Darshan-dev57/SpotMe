package dev.darshan.spotme.jobs;

import java.util.UUID;

/** A job a worker has claimed. The claimToken proves the claim is still ours when we finish. */
public record Job(long id, JobKind kind, UUID targetId, int attempts, int maxAttempts, UUID claimToken) {
}
