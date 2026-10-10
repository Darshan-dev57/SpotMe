package dev.darshan.spotme.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TokensTest {

    @Test
    void tokensAreLongAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String t = Tokens.newToken();
            assertThat(t).hasSize(43).matches("[A-Za-z0-9_-]+");
            seen.add(t);
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    void sha256MatchesKnownValue() {
        assertThat(Tokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
