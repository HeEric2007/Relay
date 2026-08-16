package com.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SigningTest {

    /** RFC 4231 test case 2 — if this passes, receivers using any HMAC-SHA256 library agree with us. */
    @Test
    void matchesTheHmacSha256SpecVector() {
        String signature = Signing.sign("Jefe", "what do ya want for nothing?");

        assertThat(signature)
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        String body = "{\"amount\":4200}";

        assertThat(Signing.sign("secret-one", body)).isNotEqualTo(Signing.sign("secret-two", body));
    }

    @Test
    void secretsAreUniqueAndPrefixed() {
        String first = Signing.newSecret();
        String second = Signing.newSecret();

        assertThat(first).startsWith("whsec_").hasSize(70);
        assertThat(first).isNotEqualTo(second);
    }
}
