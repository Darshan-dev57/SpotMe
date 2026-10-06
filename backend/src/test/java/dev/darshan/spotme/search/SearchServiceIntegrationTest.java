package dev.darshan.spotme.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.darshan.spotme.face.FaceEngine;
import dev.darshan.spotme.face.Vectors;
import dev.darshan.spotme.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

/** The SQL of the selfie search, with hand-made face vectors instead of real photos. */
class SearchServiceIntegrationTest extends IntegrationTest {

    @Autowired
    SearchService search;

    @Test
    void photoMatchesWhenAnyOfItsFacesIsCloseEnough() {
        UUID organizer = UUID.randomUUID();
        jdbc.update("INSERT INTO organizers (id, email, display_name, password_hash) VALUES (?, ?, 'Org', 'x')",
                organizer, organizer + "@example.com");
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, organizer_id, title, purpose_notice, status, consent_attested_at, expires_at)
                VALUES (?, ?, 'Search test', 'notice', 'published', now(), now() + interval '1 day')
                """, event, organizer);
        UUID guest = UUID.randomUUID();
        jdbc.update("INSERT INTO memberships (id, event_id, label, invite_token_hash) VALUES (?, ?, 'Guest', ?)",
                guest, event, guest.toString());

        float[] me = unit(0);
        UUID group = photo(event, false, me, unit(1));       // me plus someone else
        UUID stranger = photo(event, false, unit(1));        // only someone else
        UUID hidden = photo(event, true, me);                // me, but hidden by the organizer
        UUID blurry = photo(event, false, mix(0.8f));        // me, less sure

        // run it more than five times so the JDBC driver switches to a server-side prepared statement
        for (int i = 0; i < 8; i++) {
            List<SearchService.Match> matches = search.search(event, guest, me, 0.55, 200);
            assertThat(matches).extracting(SearchService.Match::photoId).containsExactly(group, blurry);
            assertThat(matches.get(0).distance()).isLessThan(0.001);
            assertThat(matches).extracting(SearchService.Match::photoId).doesNotContain(stranger, hidden);
        }
    }

    private UUID photo(UUID event, boolean hidden, float[]... faces) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO photos (id, event_id, content_sha256, status, hidden_at)
                VALUES (?, ?, ?, 'ready', CASE WHEN ? THEN now() END)
                """, id, event, id.toString(), hidden);
        for (int i = 0; i < faces.length; i++) {
            jdbc.update("""
                    INSERT INTO faces (event_id, photo_id, face_index, box_x, box_y, box_w, box_h, det_score, embedding, model_version)
                    VALUES (?, ?, ?, 0, 0, 50, 50, 0.9, ?::vector, ?)
                    """, event, id, i, Vectors.toPgVector(faces[i]), FaceEngine.MODEL_VERSION);
        }
        return id;
    }

    private static float[] unit(int axis) {
        float[] v = new float[128];
        v[axis] = 1;
        return v;
    }

    /** Mostly axis 0 with some of axis 1: cosine distance to unit(0) is about 0.03 for 0.8. */
    private static float[] mix(float share) {
        float[] v = new float[128];
        v[0] = share;
        v[1] = 1 - share;
        return v;
    }
}
