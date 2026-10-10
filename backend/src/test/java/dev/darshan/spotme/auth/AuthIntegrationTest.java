package dev.darshan.spotme.auth;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Accounts, sessions and who may call what. */
class AuthIntegrationTest extends IntegrationTest {

    @Test
    void anEmailCanOnlyRegisterOnceWhateverItsCase() {
        api.registerOrganizer("Case@Example.com");
        var again = api.post("/api/auth/register", null,
                Map.of("email", "CASE@example.COM", "name", "Someone", "password", "password123"));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).containsEntry("code", "email_taken");
    }

    @Test
    void shortPasswordsAndBadEmailsAreRefused() {
        var shortPassword = api.post("/api/auth/register", null,
                Map.of("email", "short@example.com", "name", "Short", "password", "1234567"));
        assertThat(shortPassword.getStatusCode().value()).isEqualTo(400);
        var badEmail = api.post("/api/auth/register", null,
                Map.of("email", "not-an-email", "name", "Bad", "password", "password123"));
        assertThat(badEmail.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() {
        api.registerOrganizer("known@example.com");
        var wrong = api.post("/api/auth/login", null, Map.of("email", "known@example.com", "password", "wrong-password"));
        var unknown = api.post("/api/auth/login", null, Map.of("email", "nobody@example.com", "password", "wrong-password"));
        assertThat(wrong.getStatusCode().value()).isEqualTo(401);
        assertThat(unknown.getStatusCode().value()).isEqualTo(401);
        assertThat(wrong.getBody()).isEqualTo(unknown.getBody());
    }

    @Test
    void logoutEndsTheSession() {
        String token = api.registerOrganizer("logout@example.com");
        assertThat(api.get("/api/auth/me", token).getStatusCode().value()).isEqualTo(200);
        assertThat(api.post("/api/auth/logout", token, null).getStatusCode().value()).isEqualTo(204);
        assertThat(api.get("/api/auth/me", token).getStatusCode().value()).isEqualTo(401);
        assertThat(api.get("/api/events", token).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void organizerAndAttendeeTokensOnlyOpenTheirOwnSide() {
        String org = api.registerOrganizer("sides@example.com");
        String event = api.createEvent(org, "Sides");
        String attendee = api.joinAsAttendee(org, event, "Guest");

        assertThat(api.get("/api/events", attendee).getStatusCode().value()).isEqualTo(401);
        assertThat(api.search(org, Api.fixture("hurley.jpg")).getStatusCode().value()).isEqualTo(401);
        assertThat(api.get("/api/events", null).getStatusCode().value()).isEqualTo(401);
        assertThat(api.get("/api/events", "made-up-token").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aDeclinedNoticeOrAMadeUpInviteGivesNoSession() {
        String org = api.registerOrganizer("decline@example.com");
        String event = api.createEvent(org, "Decline");
        String invite = (String) api.post("/api/events/" + event + "/invites", org, Map.of("label", "Guest"))
                .getBody().get("token");

        var declined = api.post("/api/join/" + invite + "/consent", null, Map.of("noticeVersion", 1, "agree", false));
        assertThat(declined.getStatusCode().value()).isEqualTo(400);
        assertThat(declined.getBody()).doesNotContainKey("sessionToken");

        var madeUp = api.get("/api/join/not-a-real-invite", null);
        assertThat(madeUp.getStatusCode().value()).isEqualTo(404);
        assertThat(madeUp.getBody()).containsEntry("code", "invite_invalid");
    }

    @Test
    void guestsCannotSearchBeforeTheEventIsPublished() {
        String org = api.registerOrganizer("draft@example.com");
        String event = api.createEvent(org, "Draft");
        String attendee = api.joinAsAttendee(org, event, "Early guest");
        var res = api.search(attendee, Api.fixture("hurley.jpg"));
        assertThat(res.getStatusCode().is4xxClientError()).isTrue();
    }
}
