package dev.darshan.spotme.media;

import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

/** The MinIO server used by the storage tests: the community build, since MinIO no longer publishes images. */
public final class Minio {

    public static final String IMAGE = "pgsty/minio:RELEASE.2026-08-04T00-00-00Z";

    private Minio() {
    }

    public static MinIOContainer container() {
        return new MinIOContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("minio/minio"))
                .withUserName("spotme")
                .withPassword("spotme-minio");
    }
}
