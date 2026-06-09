package io.aura;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class JwtSupportTest {

    @Test
    void sign_and_verify() {
        JwtSupport jwt = new JwtSupport("test-secret", 3600);
        String token = jwt.sign(42L);
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
        Long userId = jwt.verify(token);
        assertThat(userId).isEqualTo(42L);
    }

    @Test
    void verify_invalidSignature_returnsNull() {
        JwtSupport jwt = new JwtSupport("test-secret", 3600);
        String token = jwt.sign(1L);
        String tampered = token.substring(0, token.lastIndexOf('.')) + ".invalid";
        assertThat(jwt.verify(tampered)).isNull();
    }

    @Test
    void verify_expired_returnsNull() {
        JwtSupport jwt = new JwtSupport("test-secret", -1);
        String token = jwt.sign(1L);
        assertThat(jwt.verify(token)).isNull();
    }

    @Test
    void verify_null_returnsNull() {
        JwtSupport jwt = new JwtSupport("test-secret", 3600);
        assertThat(jwt.verify(null)).isNull();
        assertThat(jwt.verify("")).isNull();
        assertThat(jwt.verify("not.a.jwt")).isNull();
    }

    @Test
    void verify_wrongSecret_returnsNull() {
        JwtSupport jwt1 = new JwtSupport("secret-1", 3600);
        JwtSupport jwt2 = new JwtSupport("secret-2", 3600);
        String token = jwt1.sign(99L);
        assertThat(jwt2.verify(token)).isNull();
    }
}
