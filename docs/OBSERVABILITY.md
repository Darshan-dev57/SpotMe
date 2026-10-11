# Observability

What SpotMe reports about itself, where to read it, and what is kept out of the logs.

## Metrics

Local runs serve Prometheus metrics at `http://localhost:8080/actuator/prometheus`. The hosted box turns that
endpoint off (`deploy/start.sh`), because on a public site it would show traffic to anyone.

| What | Metric | Where it is counted |
|---|---|---|
| Queue depth | `spotme_jobs{state="queued"\|"running"\|"failed"}` | `QueueMetrics`, read from the jobs table every 15 s |
| Job age | `spotme_jobs_oldest_wait_seconds`: how long the oldest ready job has waited | `QueueMetrics` |
| Failed jobs and retries | `spotme_jobs_finished_total{kind, outcome}`, outcome is `indexed`, `skipped`, `discarded`, `purged`, `listed`, `retry`, `failed` or `stale` | `JobWorker` |
| Indexing throughput | `rate(spotme_jobs_finished_total{kind="INDEX_PHOTO",outcome="indexed"}[5m])` | `JobWorker` |
| Time per job | `spotme_jobs_duration_seconds{kind}` | `JobWorker` |
| Face search latency | `spotme_search_seconds{quantile="0.5"\|"0.95"}`, plus count and sum | `SearchService` |
| Uploads and upload failures | `spotme_uploads_total{result, code}`, result is `queued`, `duplicate` or `rejected` with the error code | `UploadController`, `ApiErrorHandler` |
| Auth failures | `spotme_auth_failures_total{code}` (every 401 and 403) | `ApiErrorHandler` |
| Rate limit rejections | `spotme_rate_limit_rejections_total{route}` (every 429) | `ApiErrorHandler` |
| Storage used | `spotme_storage_photos_bytes` (originals not yet purged) and Spring's `disk_free_bytes` | `QueueMetrics` |
| Database pool | `hikaricp_connections_active`, `_idle`, `_pending`, `_max` | Spring Boot |
| HTTP requests | `http_server_requests_seconds{uri, status}` | Spring Boot |

Tags are fixed words (error codes, route patterns, job kinds), never ids, emails or file names, so the number of
series stays small. `ObservabilityIntegrationTest` uploads, indexes, searches and fails logins, then reads
`/actuator/prometheus` and checks each of these metrics.

## Logs

- Every request gets an id: the caller's `X-Request-Id` if it is short and plain, otherwise a new UUID. It is sent
  back in the `X-Request-Id` response header and printed on every log line of that request, so an error a user
  reports can be found in the log.
- Every job run prints its job id the same way (`[job-42]`).
- The worker logs when it starts (with its thread count) and when it stops (with the number of jobs done).
- On the hosted box logs are JSON lines (Spring Boot's ECS format, `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`), one
  object per line with `requestId` or `jobId` as fields. Set the same variable locally to get JSON.

Local log line:

```text
2026-10-11T08:37:04.815Z  WARN [job-2] 2646105 --- [spotme] [main] dev.darshan.spotme.jobs.JobWorker : Job 2 (INDEX_PHOTO) failed for good: file_missing
```

## What never goes into a log

- passwords, session tokens, invite tokens and signed photo links;
- photo or selfie bytes, face vectors and file names from uploads;
- request bodies. Unexpected errors log only the exception type and message.

`ObservabilityIntegrationTest` registers with a password, invites a guest and searches, then checks that the
password and every token are absent from the captured log output.
