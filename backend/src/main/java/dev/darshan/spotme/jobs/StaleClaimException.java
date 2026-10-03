package dev.darshan.spotme.jobs;

/**
 * Our lease expired and another worker re-claimed the job (or it was cancelled).
 * Thrown inside the result transaction so nothing we computed is saved.
 */
public class StaleClaimException extends RuntimeException {

    public StaleClaimException(long jobId) {
        super("Claim on job " + jobId + " is no longer valid");
    }
}
