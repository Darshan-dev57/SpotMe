# Threat model

What SpotMe protects, who might attack it, and what stops them. Each row points to the code that does it
and the test that proves it. SpotMe holds private event photos and face vectors, so most of this is about
keeping one person's data away from everyone else.

## Contents

- [What is worth protecting](#what-is-worth-protecting)
- [Who uses it and what they are trusted with](#who-uses-it-and-what-they-are-trusted-with)
- [Threats](#threats)
- [Known gaps](#known-gaps)

## What is worth protecting

| Asset | Where it lives |
|---|---|
| Event photos and thumbnails | Storage (local disk under generated keys), served only through signed links |
| Face vectors of everyone in the photos | `faces` table, scoped by `event_id` |
| A guest's selfie | Only in memory during one request; never stored |
| A guest's face vector, if they turn on alerts | `photo_alerts`, deleted on opt-out, withdrawal, revoke, notice change or event end |
| Which photos a guest was matched to | `guest_matches` (photo ids only), used to check ZIP downloads |
| Organizer accounts | `organizers` (BCrypt password hash), `sessions` (SHA-256 of the token) |
| Invite links | `memberships.invite_token_hash` (SHA-256 of the token) |

## Who uses it and what they are trusted with

- **Organizer**: signed in with email and password. Owns their events: uploads, invites, publishing, removal
  decisions. Never sees selfies, face vectors or which photos a guest matched.
- **Guest**: holds a personal invite link and a session made when they agree to the notice. Can search one
  event (their own), download what they were shown, ask for removal, withdraw.
- **Anyone else on the internet**: can reach every endpoint, has no token.

## Threats

### 1. Stolen or forwarded invite link

Someone gets a guest's link (forwarded chat, shoulder surfing) and searches as that guest.

- The link is a 256-bit random token stored only as a hash, and travels after `#` so it is not sent in
  `Referer` headers or server logs.
- The person still needs a selfie that matches; the link alone shows nothing. Results are only the photos
  the selfie matches (plus photos the organizer made public).
- The organizer can revoke the invite. Revoking ends the session at once and the link stops working, also
  for a new consent. Tests: `PipelineIntegrationTest.withdrawnOrRevokedAttendeesLoseAccessImmediately`,
  `AccessRulesIntegrationTest.aRevokedInviteLinkCannotBeOpenedOrAcceptedAgain`.
- Gap: a link is not tied to a verified identity (see [Known gaps](#known-gaps)).

### 2. Cross-event data leaks

A guest of event A, or the organizer of event B, tries to reach event A's photos.

- Guest sessions carry their event; every search filters `faces.event_id` by it. Test:
  `PipelineIntegrationTest.attendeeFindsTheGroupPhotoAndOnlyFromTheirOwnEvent`.
- Every organizer endpoint calls `EventAccess.requireOwner`, which answers 404 (not 403) so event ids can't be
  probed. Test: `AccessRulesIntegrationTest.anotherOrganizerGetsNotFoundOnEveryEndpointOfMyEvent` (20 endpoints,
  and checks nothing changed).
- Photo ids sent by a guest (removal, wrong match, ZIP) are checked against the guest's event. Tests:
  `AttendeeIntegrationTest.cannotAskToRemoveAPhotoFromAnotherEvent`,
  `ZipDownloadIntegrationTest.aGuestCannotZipAPrivatePhotoTheyWereNotShown`,
  `PhotoVisibilityIntegrationTest.photosOfAnotherEventCannotBeChanged`.
- Even a correctly signed photo link is checked live against the database when used, so a link made for a
  guest of another event opens nothing. Test:
  `MediaLinkIntegrationTest.aGuestOfOneEventCannotOpenAPhotoOfAnotherEventEvenWithAValidSignature`.

### 3. SSRF through Google Drive import

An organizer pastes a link (or Google answers with a redirect) that points the server at an internal address,
for example the cloud metadata service or the actuator port.

- Only the folder id is taken from the pasted link; the server builds the Google URLs itself.
- `DriveClient` talks only https to a fixed list of Google hosts on port 443, with no user info in the URL.
- Redirects are not followed automatically. Every hop is checked against the same list before it is
  requested. Tests: `DriveClientTest.onlyTalksToGoogleOverHttps`,
  `DriveClientTest.aRedirectToAnythingButGoogleIsRefused` (metadata IP, localhost, private ranges, IPv6
  loopback, look-alike hosts, `file:`), `DriveLinksTest.refusesOtherSitesFilesAndPlainHttp`.
- Timeouts and a byte limit on every response. Test: `DriveClientTest.stopsReadingPastTheByteLimit`.
- Gap: the allowed host names are trusted to resolve to Google; DNS for those names is not re-checked.

### 4. Malicious image uploads

Files that are not images, decompression bombs, oversized files, images carrying scripts or metadata.

- The type is read from the first bytes, never the file name or the `Content-Type` header.
- The pixel count is read from the header and refused before any decoding.
- Size caps: 20 MB per photo (also enforced by Tomcat's multipart limits), the same cap on selfies.
- Every accepted photo is decoded and re-encoded as a new JPEG, which drops EXIF (GPS) and anything hidden in
  the original file. Stored under keys made from generated ids, never the uploaded name.
- At most 2 decodes at once, so a burst of large files can't exhaust memory.
- Tests: `ImageProcessorTest` (11 tests), `UploadIntegrationTest.badFilesGetAClearErrorAndNothingIsStored`,
  `AccessRulesIntegrationTest.oversizedAndFakeImagesAreRefusedAndNothingIsStored` (oversized file, a PNG header
  claiming 10000 x 10000 pixels, HTML renamed to `.jpg`, oversized selfie; errors are short JSON with no stack
  trace; nothing is stored).

### 5. Face-search abuse

Using search to find out whether a person is at an event, or to run many faces through it.

- Search needs a guest session for a published event, after consent. Test:
  `AuthIntegrationTest.guestsCannotSearchBeforeTheEventIsPublished`,
  `AuthIntegrationTest.aDeclinedNoticeOrAMadeUpInviteGivesNoSession`.
- One face per selfie; group selfies and photos with no face are refused. Test:
  `PipelineIntegrationTest.selfieWithSeveralPeopleOrNoFaceIsRefused`.
- A per-guest limit on face lookups (10 per minute), shared by search and the alert opt-in so switching
  endpoints does not add limits together. Test: `AccessRulesIntegrationTest.searchAndAlertsShareOneRateLimit`.
- The raw-distance endpoint used for evaluation is off unless the eval profile is on. Same test.
- Other limits: login per email, ZIP downloads per guest, visitor invites per address. The address comes from
  Tomcat's forwarded-header handling, so a made-up `X-Forwarded-For` entry does not reset it. Tests:
  `AttendeeIntegrationTest.organizerLoginIsRateLimitedPerEmail`, `ZipDownloadIntegrationTest.zipsAreRateLimitedPerGuest`,
  `DemoIntegrationTest.aMadeUpForwardedForEntryDoesNotDodgeTheLimit`.
- Gap: limits are kept in memory, per process.

### 6. Signed URL guessing

Guessing or editing photo links to open other photos or keep them forever.

- Links carry photo id, variant, subject (which guest or organizer) and expiry, signed with HMAC-SHA256;
  the signature is compared in constant time. Editing any part breaks it. Tests:
  `MediaSignerTest.rejectsAnyChangedPart`, `PipelineIntegrationTest.attendeeFindsTheGroupPhotoAndOnlyFromTheirOwnEvent`
  (thumb link edited to original: 403).
- Links expire after 10 minutes (ZIP links after 2). Tests: `MediaSignerTest.rejectsExpiredLinks`,
  `MediaLinkIntegrationTest.anExpiredLinkStopsWorking`.
- Access is re-checked when a link is used: hiding, removal, withdrawal or revoking closes links already
  handed out. Test: `AttendeeIntegrationTest.hiddenPhotoDisappearsFromSearchAndItsLinksStopWorking`.
- In production the signing secret comes from the environment; without one a random secret is made at start.

### 7. Incomplete deletion

A removal, withdrawal or expired event leaves files, thumbnails or vectors behind.

- A removal request hides the photo at once (search, public list, links, ZIP). Approval purges the original,
  the thumbnail and the face vectors, and blocks the same bytes from being uploaded again.
- Event expiry purges every photo, thumbnail, vector, guest, alert and match of the event.
- A deletion epoch stops an indexing job that was running during the deletion from writing vectors back.
- An orphan cleaner removes files with no database row.
- Tests: `PrivacyIntegrationTest` (6 tests, including
  `removalHidesAtOnceAndApprovalPurgesFilesVectorsAndBlocksReupload`, `expiredEventIsPurgedCompletely`,
  `lateCommitIsDiscardedWhenTheDeletionEpochMoved`), `ZipDownloadIntegrationTest.aHiddenPhotoLeavesTheZip`,
  `AlertIntegrationTest` (alert vectors deleted in every case).
- The selfie itself is never stored. Test: `AccessRulesIntegrationTest.theSelfieIsNeverStored` (no new file,
  face, photo, alert or job row after a search).

### 8. Organizer access to guest data

An organizer trying to learn more about guests than they need.

- Organizers see a guest's label (the name they typed), consent and revoke times, and removal requests with
  the photos named in them. There is no endpoint that returns selfies, guest face vectors or which photos a
  guest matched.
- A guest's alert vector is only used to check new uploads; it is never returned by the API.
- Guest responses never include face boxes or vectors either. Test:
  `AttendeeIntegrationTest.guestResultsCarryNoFaceBoxesOrVectors`.
- Gap: organizers see every photo they uploaded, including the ones a guest asked to remove, until they decide.
  That is by design: they decide the request.

## Known gaps

- Invite links are not tied to a verified identity. A forwarded link plus a matching face works until the
  organizer revokes it.
- Rate limits live in memory. Several API processes would each have their own limits; a shared store (for
  example Redis or a Postgres table) would be needed.
- Photo files are on one machine's disk by default; see the storage section of the README.
- No malware scanning beyond decoding and re-encoding; the stored file is always our own re-encoded JPEG.
- Drive host names are trusted to resolve to Google.
- A match is a likely match (threshold 0.51 on SFace cosine distance), not proof of identity.
