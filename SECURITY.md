# Security

## Reporting a problem

Please report security problems privately through GitHub: open the repository's **Security** tab and choose
**Report a vulnerability**. Please do not open a public issue for them.

Include what you found, how to reproduce it and what it affects. I will reply as soon as I can and credit you in
the changelog if you want.

## Supported code

Only the latest commit on `main` and the live site are maintained.

## What is in scope

- Reading or changing another organizer's event, or another event's photos as a guest
- Opening photos through edited, expired or someone else's links
- Getting the server to fetch internal addresses (Drive import)
- Uploads that crash the app, use too much memory or get stored without being re-encoded
- Selfies, face vectors or tokens that end up stored, logged or returned when they should not be
- Getting around the rate limits

Out of scope: denial of service by sheer traffic, and the known gaps listed in
[docs/THREAT_MODEL.md](docs/THREAT_MODEL.md#known-gaps).

## How SpotMe is protected

The threats, the defences and the tests that prove them are in [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).
In short:

- Organizer passwords use BCrypt; session and invite tokens are random 256-bit values stored as SHA-256 hashes.
- Every organizer endpoint checks ownership and answers 404 for other organizers' events.
- Guests can only search their own event, after agreeing to its notice, with one face per selfie.
- Photos are served only through HMAC-signed links that expire and are re-checked against the database.
- Uploads are identified by their bytes, size and pixel limits are checked before decoding, and every photo is
  re-encoded (EXIF removed).
- The selfie is never written to disk or to the database.
- Errors are short JSON without stack traces or internal messages.

## Running it safely

- Set `SPOTME_MEDIA_SECRET` to a long random value in production. Without it a random one is made at startup
  and links stop working after a restart.
- Set `SPOTME_DB_PASSWORD`; never use the local default outside your machine.
- Keep only `health` and `info` exposed on the actuator endpoint (the default in `application.properties`).
- Put the app behind one reverse proxy that adds `X-Forwarded-For`; the app trusts that header only from
  private addresses.
