package io.aura;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class JwtSupportTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void blankSecret_isRejected() {
        assertThatThrownBy(() -> new JwtSupport(" ", 3600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }

    @Test
    void sign_and_verify_long() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign(42L);
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
        String subject = jwt.verify(token);
        assertThat(subject).isEqualTo("42");
    }

    @Test
    void sign_and_verify_string() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign("uuid-abc-123");
        String subject = jwt.verify(token);
        assertThat(subject).isEqualTo("uuid-abc-123");
    }

    @Test
    void verify_invalidSignature_returnsNull() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign(1L);
        String tampered = token.substring(0, token.lastIndexOf('.')) + ".invalid";
        assertThat(jwt.verify(tampered)).isNull();
    }

    @Test
    void nonPositiveLifetimeIsRejected() {
        assertThatThrownBy(() -> new JwtSupport(SECRET, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void verify_null_returnsNull() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        assertThat(jwt.verify(null)).isNull();
        assertThat(jwt.verify("")).isNull();
        assertThat(jwt.verify("not.a.jwt")).isNull();
    }

    @Test
    void verify_wrongSecret_returnsNull() {
        JwtSupport jwt1 = new JwtSupport("11111111111111111111111111111111", 3600);
        JwtSupport jwt2 = new JwtSupport("22222222222222222222222222222222", 3600);
        String token = jwt1.sign(99L);
        assertThat(jwt2.verify(token)).isNull();
    }

    @Test
    void sign_and_verify_subjectWithQuotes() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign("user\"name");
        String subject = jwt.verify(token);
        assertThat(subject).isEqualTo("user\"name");
    }

    @Test
    void sign_and_verify_subjectWithBackslash() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign("domain\\user");
        String subject = jwt.verify(token);
        assertThat(subject).isEqualTo("domain\\user");
    }

    @Test
    void sign_and_verify_subjectWithColon() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign("user:123");
        assertThat(jwt.verify(token)).isEqualTo("user:123");
    }

    @Test
    void sign_and_verify_subjectWithSpecialChars() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign("admin@example.com");
        assertThat(jwt.verify(token)).isEqualTo("admin@example.com");
    }

    @Test
    void sign_and_verify_subjectWithUnicode() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign("用户名");
        assertThat(jwt.verify(token)).isEqualTo("用户名");
    }

    @Test
    void sign_and_verify_subjectWithMultipleEscapes() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign("path\\to\\\"file\"");
        assertThat(jwt.verify(token)).isEqualTo("path\\to\\\"file\"");
    }

    @Test
    void verify_emptySubject_returnsNull() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign("");
        assertThat(jwt.verify(token)).isNull();
    }

    @Test
    void sign_and_verify_subjectWithJsonLikeContent() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);
        String token = jwt.sign("{\"nested\":\"value\"}");
        assertThat(jwt.verify(token)).isEqualTo("{\"nested\":\"value\"}");
    }

    @Test
    void productionJwt_rejectsWeakSecretAndNonPositiveLifetime() {
        assertThatThrownBy(() -> new JwtSupport("too-short", 3600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new JwtSupport(SECRET, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void rejectsAnAlgorithmTheHeaderClaimsInsteadOfTheOneWeVerifyWith() {
        String token = signatureOver("{\"alg\":\"none\",\"typ\":\"JWT\"}",
                "{\"sub\":\"1\",\"exp\":" + (now() + 3600) + "}");

        assertThat(new JwtSupport(SECRET, 3600).verify(token)).isNull();
    }

    @Test
    void rejectsAnUnexpectedTypeHeader() {
        String token = signatureOver("{\"alg\":\"HS256\",\"typ\":\"JWS\"}",
                "{\"sub\":\"1\",\"exp\":" + (now() + 3600) + "}");

        assertThat(new JwtSupport(SECRET, 3600).verify(token)).isNull();
    }

    @Test
    void enforcesTheConfiguredIssuerAndAudience() {
        JwtSupport scoped = new JwtSupport(SECRET, 3600, "aura", "web");
        String token = scoped.sign("user-1");

        assertThat(scoped.verify(token)).isEqualTo("user-1");
        assertThat(new JwtSupport(SECRET, 3600, "other", "web").verify(token)).isNull();
        assertThat(new JwtSupport(SECRET, 3600, "aura", "other").verify(token)).isNull();
    }

    @Test
    void acceptsExpiredAndNotYetValidTokensOnlyWithinTheClockSkew() {
        long now = now();
        JwtSupport lenient = new JwtSupport(SECRET, 3600, null, null, 60);
        JwtSupport strict = new JwtSupport(SECRET, 3600, null, null, 0);

        String justExpired = signatureOver("{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                "{\"sub\":\"u\",\"exp\":" + (now - 10) + "}");
        assertThat(lenient.verify(justExpired)).isEqualTo("u");
        assertThat(strict.verify(justExpired)).isNull();

        String notYetValid = signatureOver("{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                "{\"sub\":\"u\",\"exp\":" + (now + 3600) + ",\"nbf\":" + (now + 10) + "}");
        assertThat(lenient.verify(notYetValid)).isEqualTo("u");
        assertThat(strict.verify(notYetValid)).isNull();
    }

    @Test
    void rejectsASubjectThatDecodesToControlCharacters() {
        JwtSupport jwt = new JwtSupport(SECRET, 3600);

        assertThat(jwt.verify(jwt.sign("user\nname"))).isNull();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    /** Mints a token whose signature is genuine, leaving the header and claims under test control. */
    private static String signatureOver(String headerJson, String payloadJson) {
        String header = encode(headerJson);
        String payload = encode(payloadJson);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return header + "." + payload + "." + signature;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
