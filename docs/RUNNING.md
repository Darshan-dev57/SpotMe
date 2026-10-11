# Running SpotMe

How to run SpotMe on your own machine, what each step does, and how the whole flow was checked from a fresh
clone.

## Contents

- [One command](#one-command)
- [What the script does](#what-the-script-does)
- [Step by step](#step-by-step)
- [Checking the full flow by hand](#checking-the-full-flow-by-hand)
- [Clean clone check](#clean-clone-check)
- [Problems](#problems)

## One command

Needs Docker, JDK 21, Maven 3.9+ and Node.js 20+.

```bash
git clone https://github.com/Darshan-dev57/SpotMe.git
cd SpotMe
./scripts/start.sh
```

When it is ready it prints:

```text
SpotMe is running at http://localhost:8080

  Organizer test account   organizer@example.com / <random password>   (sign in at http://localhost:8080/organizer)
  Sample event             on the home page, "Try it with sample photos" (indexing takes a minute)
  Log                      data/spotme.log
```

Stop it with Ctrl-C. PostgreSQL keeps running in Docker until `docker compose down`. Running the script again
skips the steps that are already done. Use another port with `PORT=8090 ./scripts/start.sh`.

## What the script does

| Step | What happens | Skipped when |
|---|---|---|
| 1 | `docker compose up -d --wait`: PostgreSQL 16 with pgvector on localhost:5434 | (always run, it is quick) |
| 2 | `scripts/fetch-models.sh`: the YuNet and SFace models from the OpenCV Zoo, SHA-256 checked | both files are there and match |
| 3 | `npm ci` and `npm run build` in `web/`: the static web UI in `web/out` | `web/out` is newer than every file in `web/src` |
| 4 | `scripts/fetch-sample-photos.sh`: 36 public domain NASA photos into `data/sample-photos` | the folder is not empty |
| 5 | `mvn spring-boot:run` in `backend/` with the UI, the sample event and the right guest link address switched on | |
| 6 | registers `organizer@example.com` with a random password kept in `data/test-account.txt` | the account exists |

The app runs the API, the indexing worker and the web UI in one process. Its log is `data/spotme.log`.

## Step by step

The same thing by hand:

```bash
docker compose up -d
./scripts/fetch-models.sh
cd web && npm ci && npm run build && cd ..
cd backend
SPOTME_WEB_DIR=../web/out SPOTME_WEB_ORIGIN=http://localhost:8080 mvn spring-boot:run
```

`SPOTME_WEB_ORIGIN` is the address guest invite links point to. Without it they point to
`http://localhost:3000`, the Next.js dev server, which is right only when you run the UI with `npm run dev`.

Add the sample event with `./scripts/fetch-sample-photos.sh` and
`SPOTME_SAMPLE_ENABLED=true SPOTME_SAMPLE_PHOTOSDIR=../data/sample-photos` on the last command.

## Checking the full flow by hand

1. Open http://localhost:8080/organizer and create an account (or sign in with the test account).
2. Create an event and tick the consent box.
3. Upload a few photos (or a whole folder). The progress bar shows queued, indexed and failed counts live.
4. Press *Publish to guests*.
5. On the *Guests* tab, type a name and press *Create links*. Copy the link.
6. Open the link in a private window and read the notice. Press *I agree, continue*.
7. Search with a selfie. Only the photos that person is in come back.
8. Download one photo or all of them as a ZIP.
9. *Remove me* sends a removal request for the photos where the guest is alone: they disappear at once. The
   organizer approves it on the *Removals* tab, which deletes the file, its thumbnail and its face vectors.
10. *Withdraw consent* ends the guest's access; old photo links stop working.

To see that the data is really gone:

```bash
docker exec spotme-postgres psql -U spotme -d spotme -c \
  "SELECT status, count(*) FROM photos GROUP BY status"            # removed photos are 'purged'
docker exec spotme-postgres psql -U spotme -d spotme -c \
  "SELECT count(*) FROM faces f JOIN photos p ON p.id = f.photo_id WHERE p.status = 'purged'"   # 0
ls data/storage/events/<event id>/                                  # no files left for the purged photo
```

## Clean clone check

Run on 2026-10-11 from a fresh `git clone` into an empty folder, following the README commands exactly, with
steps 5 to 14 done in a real browser (Chromium at phone size) by a Playwright script.

| Step | Result | What was checked |
|---|---|---|
| 1. Start PostgreSQL | pass | `docker compose up -d`, healthy |
| 2. Download the face models | pass | `./scripts/fetch-models.sh`, both checksums match |
| 3. Build the web UI | pass | `npm ci && npm run build`, 29 s |
| 4. Start the backend | pass, after a README fix | `mvn spring-boot:run` up in about 10 s; guest links pointed to `localhost:3000` until `SPOTME_WEB_ORIGIN` was added to the command |
| 5. Create an organizer | pass | account created in the UI |
| 6. Create an event | pass | event created with the consent box |
| 7. Upload photos | pass | 4 photos uploaded, all 4 indexed |
| 8. Create a guest invite | pass | link created on the Guests tab |
| 9. Open the guest link in a private browser | pass | a separate browser context with no stored data |
| 10. Give consent | pass | notice read, *I agree* gives a session |
| 11. Search with a selfie | pass | Hurley's selfie finds his 2 photos (group + solo) |
| 12. Download matching photos | pass | ZIP with exactly those 2 photos |
| 13a. Request removal | pass | solo photo gone from the guest's results at once; organizer approved on the Removals tab |
| 13b. Withdraw consent | pass | *Withdraw consent* shows "You have left this event" |
| 14a. Data is really deleted | pass | photo row `purged`, 0 face vectors, 0 files (original and thumbnail) for it on disk, 0 guest sessions, membership marked withdrawn |
| 14b. Old links stop working | pass | a photo link handed out before answers HTTP 403 |

`./scripts/start.sh` was then run in another fresh clone: every step passed, the test account could sign in,
the sample event indexed all 36 photos and the sample Behnken selfie found 15 photos.

## Problems

| Symptom | Fix |
|---|---|
| `Something is already running on port 8080` | stop the other app, or `PORT=8090 ./scripts/start.sh` |
| `docker compose up` fails | start Docker Desktop / the Docker service first |
| port 5434 is taken | another PostgreSQL uses it; stop it or change the port in `docker-compose.yml` and `SPOTME_DB_URL` |
| guest links open `localhost:3000` | set `SPOTME_WEB_ORIGIN` to the address you open the app on |
| `face models missing` in the log | run `./scripts/fetch-models.sh` (needs internet once) |
| the app stops while starting | read the end of `data/spotme.log`; most often the database is not up yet |
