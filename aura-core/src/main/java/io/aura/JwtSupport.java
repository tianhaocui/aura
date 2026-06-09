package io.aura;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class JwtSupport {

    private final String secret;
    private final long expireSeconds;

    public JwtSupport(String secret, long expireSeconds) {
        this.secret = secret;
        this.expireSeconds = expireSeconds;
    }

    public String sign(long userId) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        long exp = System.currentTimeMillis() / 1000 + expireSeconds;
        String payload = base64Url("{\"sub\":\"" + userId + "\",\"exp\":" + exp + "}");
        String signature = hmacSha256(header + "." + payload);
        return header + "." + payload + "." + signature;
    }

    public Long verify(String token) {
        if (token == null || token.isBlank()) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        String expectedSig = hmacSha256(parts[0] + "." + parts[1]);
        if (!java.security.MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) return null;
        try {
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            long exp = extractLong(json, "exp");
            if (exp > 0 && System.currentTimeMillis() / 1000 > exp) return null;
            long sub = extractLong(json, "sub");
            return sub != 0 ? sub : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static long extractLong(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return 0;
        int start = idx + search.length();
        if (start >= json.length()) return 0;
        if (json.charAt(start) == '"') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return 0;
        return Long.parseLong(json.substring(start, end));
    }

    private JwtSupport() { this("", 0); }
}
