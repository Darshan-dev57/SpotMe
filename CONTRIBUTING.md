# Contributing to SpotMe

Thanks for taking a look. SpotMe is a small project, so the process is short.

## Set up

Needs JDK 21, Maven 3.9+, Node.js 20+ and Docker. `./scripts/start.sh` runs everything locally;
[docs/RUNNING.md](docs/RUNNING.md) explains each step and how to run the parts by hand. Settings are listed
in [.env.example](.env.example).

## Before you open a pull request

All of these must pass; GitHub Actions runs the same checks on every push.

```bash
cd backend && mvn verify                    # unit and integration tests (Testcontainers needs Docker)
cd web && npm run lint && npm test && npm run build
```

If you change something the README or `docs/` describe, update them in the same pull request.

## How the code is organised

- Business rules live in the Java backend (Spring Boot). The web UI only calls the API and shows the result.
- One package per area under `backend/src/main/java/dev/darshan/spotme/` (events, jobs, media, search,
  privacy, ...). [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) has the map.
- Database changes are new Flyway migrations in `backend/src/main/resources/db/migration/`. Never edit a
  migration that has already been merged: databases that ran it would fail to start.
- Anything that touches photos, faces, tokens or deletion needs a test that shows the rule holds, like the
  ones listed in [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

## Commits and branches

- Work on a branch named after the change (`feature/...`, `fix/...`) and merge it with `--no-ff`.
- Small commits with short imperative messages: "Add a request id to every log line", not "added stuff".
- Never commit photos, face data, models, `.env` files or secrets. LFW images and evaluation data stay in
  `eval/data/`, which git ignores.

## Reporting a security problem

Please don't open a public issue. See [SECURITY.md](SECURITY.md).
