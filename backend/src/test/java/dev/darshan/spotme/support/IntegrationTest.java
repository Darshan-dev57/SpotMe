package dev.darshan.spotme.support;

import dev.darshan.spotme.jobs.Job;
import dev.darshan.spotme.jobs.JobQueue;
import dev.darshan.spotme.jobs.JobWorker;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Base class for tests that need the real app + a real Postgres with pgvector.
 * One container is started for the whole test run (Testcontainers "singleton" pattern).
 * The background worker is switched off; tests run jobs themselves with {@link #drainJobs()}
 * so every step is deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spotme.worker.enabled=false",
        "spotme.search.per-minute-limit=1000",
        "spotme.media-secret=test-secret"
})
public abstract class IntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static final Path STORAGE;

    static {
        POSTGRES.start();
        try {
            STORAGE = Files.createTempDirectory("spotme-test-storage");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spotme.storage-dir", STORAGE::toString);
        registry.add("spotme.models-dir", () -> System.getProperty("spotme.models.dir", "../models"));
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JobQueue queue;

    @Autowired
    protected JobWorker worker;

    protected Api api;

    @BeforeEach
    void setUpApi() {
        api = new Api("http://localhost:" + port);
        // each test starts with an empty queue; events and photos are isolated by their own ids
        jdbc.update("DELETE FROM jobs");
    }

    protected Path storageDir() {
        return STORAGE;
    }

    /** Runs queued jobs one by one, like a single worker thread would, until none are ready. */
    protected int drainJobs() {
        int ran = 0;
        while (true) {
            List<Job> jobs = queue.claim("test-worker", 1, Duration.ofMinutes(1));
            if (jobs.isEmpty()) {
                return ran;
            }
            worker.runOne(jobs.get(0));
            ran++;
        }
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
