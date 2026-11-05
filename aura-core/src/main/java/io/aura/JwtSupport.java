package io.aura;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class JwtSupport {

    private static final long DEFAULT_CLOCK_SKEW_SECONDS = 30;

    private final byte[] secret;
    private final long expireSeconds;
    private final String issuer;
    private final String audience;
    private final long clockSkewSeconds;

    public JwtSupport(String secret, long expireSeconds) {
        this(secret, expireSeconds, null, null, DEFAULT_CLOCK_SKEW_SECONDS);
    }

    public JwtSupport(String secret, long expireSeconds, String issuer, String audience) {
        this(secret, expireSeconds, issuer, audience, DEFAULT_CLOCK_SKEW_SECONDS);
    }

    public JwtSupport(String secret, long expireSeconds, String issuer, String audience,
                      long clockSkewSeconds) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret must not be blank");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        }
        if (expireSeconds <= 0) {
            throw new IllegalArgumentException("JWT expiration must be positive");
        }
        if (clockSkewSeconds < 0) {
            throw new IllegalArgumentException("JWT clock skew cannot be negative");
        }
        this.secret = secretBytes;
        this.expireSeconds = expireSeconds;
        this.issuer = issuer;
        this.audience = audience;
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public String sign(long userId) {
        return sign(String.valueOf(userId));
    }

    public String sign(String subject) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        long now = System.currentTimeMillis() / 1000;
        StringBuilder payload = new StringBuilder("{\"sub\":\"").append(escape(subject))
                .append("\",\"nbf\":").append(now)
                .append(",\"exp\":").append(now + expireSeconds);
        if (issuer != null) payload.append(",\"iss\":\"").append(escape(issuer)).append('"');
        if (audience != null) payload.append(",\"aud\":\"").append(escape(audience)).append('"');
        payload.append('}');
        String encodedPayload = base64Url(payload.toString());
        String signature = hmacSha256(header + "." + encodedPayload);
        return header + "." + encodedPayload + "." + signature;
    }

    public String verify(String token) {
        if (token == null || token.isBlank()) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        String expectedSig = hmacSha256(parts[0] + "." + parts[1]);
        if (!java.security.MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) return null;
        try {
            String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            // The signature only says the bytes are ours; the algorithm and type say how to read
            // them. Accepting another verifier's header would make the JWT "none" downgrade a
            // matter of which library the other side happened to use.
            if (!"HS256".equals(extractString(header, "alg"))) return null;
            String typ = extractString(header, "typ");
            if (typ != null && !"JWT".equalsIgnoreCase(typ)) return null;

            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            long now = System.currentTimeMillis() / 1000;
            long exp = extractLong(json, "exp");
            if (exp <= 0 || now - clockSkewSeconds > exp) return null;
            long nbf = extractLong(json, "nbf");
            if (nbf > 0 && now + clockSkewSeconds < nbf) return null;
            if (issuer != null && !issuer.equals(extractString(json, "iss"))) return null;
            if (audience != null && !audience.equals(extractString(json, "aud"))) return null;
            String sub = extractString(json, "sub");
            if (sub == null || sub.isEmpty() || containsControlCharacter(sub)) return null;
            return sub;
        } catch (Exception e) {
            return null;
        }
    }

    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Keeps the claim JSON well-formed even when a subject carries a newline or a quote. */
    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    /** Index just past the expected value marker, tolerating whitespace around the colon. */
    private static int valueStart(String json, String key, char expected) {
        String search = "\"" + key + "\"";
        int index = json.indexOf(search);
        if (index < 0) return -1;
        int i = index + search.length();
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != ':') return -1;
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != expected) return -1;
        return i + 1;
    }

    private static long extractLong(String json, String key) {
        int start = valueStart(json, key, '"');
        if (start >= 0) {
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
            return end == start ? 0 : Long.parseLong(json.substring(start, end));
        }
        String search = "\"" + key + "\"";
        int index = json.indexOf(search);
        if (index < 0) return 0;
        int i = index + search.length();
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != ':') return 0;
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int end = i;
        if (end < json.length() && json.charAt(end) == '-') end++;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return end == i ? 0 : Long.parseLong(json.substring(i, end));
    }

    private static String extractString(String json, String key) {
        int start = valueStart(json, key, '"');
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> { sb.append('\\'); sb.append(next); }
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

}
