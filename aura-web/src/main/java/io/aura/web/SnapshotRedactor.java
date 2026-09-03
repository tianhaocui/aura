package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.snapshot.SnapshotOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class SnapshotRedactor {

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\\s]+");
    private final SnapshotOptions options;

    SnapshotRedactor(SnapshotOptions options) {
        this.options = options;
    }

    Map<String, String> headers(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) return result;
        for (var entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key != null && options.redactHeaders().contains(key.toLowerCase(Locale.ROOT))) {
                result.put(key, "<redacted>");
            } else {
                result.put(key, truncate(redactText(entry.getValue()), options.maxBodySize()));
            }
        }
        return result;
    }

    Map<String, String> query(Map<String, String> query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null) return result;
        query.forEach((key, value) -> result.put(key,
                isSensitiveField(key) ? "<redacted>" : truncate(redactText(value), options.maxBodySize())));
        return result;
    }

    String path(String path) {
        if (path == null || path.isBlank()) return path;
        try {
            URI uri = URI.create(path);
            String rawPath = uri.getRawPath();
            if (rawPath == null) rawPath = path.split("[?#]", 2)[0];
            String query = sanitizeRawQuery(uri.getRawQuery());
            return query == null || query.isEmpty() ? rawPath : rawPath + "?" + query;
        } catch (IllegalArgumentException ignored) {
            return truncate(redactFieldText(path), options.maxBodySize());
        }
    }

    String url(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null && uri.getRawAuthority() == null) {
                return path(value);
            }
            String authority = uri.getRawAuthority();
            int userInfoEnd = authority == null ? -1 : authority.lastIndexOf('@');
            if (userInfoEnd >= 0) authority = authority.substring(userInfoEnd + 1);
            String rawTarget = (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            String sanitized = uri.getScheme() + "://" + (authority == null ? "" : authority)
                    + "/.aura-redacted/" + sha256(rawTarget);
            return truncate(sanitized, options.maxExternalBodySize());
        } catch (Exception ignored) {
            return truncate(redactFieldText(value), options.maxExternalBodySize());
        }
    }

    String exceptionText(String value) {
        if (value == null) return null;
        return truncate(redactText(value), options.maxStackTraceSize());
    }

    Object body(Object body, int maxSize) {
        if (body == null) return null;
        Object jsonValue;
        if (body instanceof String s) {
            try {
                jsonValue = JSON.parse(s);
            } catch (Exception ignored) {
                return truncate(redactText(s), maxSize);
            }
        } else {
            jsonValue = JSON.parse(JSON.toJSONString(body));
        }
        return bounded(redact(jsonValue, maxSize), maxSize);
    }

    Object artifact(Object value) {
        return body(value, options.maxExternalBodySize());
    }

    @SuppressWarnings("unchecked")
    private Object redact(Object value, int maxSize) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isSensitiveField(key)) {
                    result.put(key, "<redacted>");
                } else {
                    result.put(key, redact(entry.getValue(), maxSize));
                }
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(redact(item, maxSize));
            return result;
        }
        if (value instanceof String s) return truncate(redactText(s), maxSize);
        return value;
    }

    boolean isSensitiveField(String key) {
        if (key == null) return false;
        for (String redacted : options.redactFields()) {
            if (key.equalsIgnoreCase(redacted)
                    || key.toLowerCase(Locale.ROOT).contains(redacted.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String redactText(String text) {
        if (text == null) return null;
        Matcher matcher = URL_PATTERN.matcher(text);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(url(matcher.group())));
        }
        matcher.appendTail(sanitized);
        return redactFieldText(sanitized.toString());
    }

    private String redactFieldText(String text) {
        String result = text;
        for (String field : options.redactFields()) {
            result = result.replaceAll("(?i)(\"" + java.util.regex.Pattern.quote(field)
                    + "\"\\s*:\\s*\")[^\"]*(\")", "$1<redacted>$2");
            result = result.replaceAll("(?i)(" + java.util.regex.Pattern.quote(field)
                    + "=)[^&\\s]+", "$1<redacted>");
        }
        return result;
    }

    private String sanitizeRawQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return rawQuery;
        StringBuilder result = new StringBuilder(rawQuery.length());
        for (String part : rawQuery.split("&", -1)) {
            if (result.length() > 0) result.append('&');
            int equals = part.indexOf('=');
            String rawKey = equals < 0 ? part : part.substring(0, equals);
            String rawValue = equals < 0 ? "" : part.substring(equals + 1);
            String key;
            try {
                key = java.net.URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                key = rawKey;
            }
            result.append(rawKey).append('=');
            if (isSensitiveField(key)) {
                result.append("%3Credacted%3E");
            } else {
                result.append(truncate(redactText(rawValue), options.maxExternalBodySize()));
            }
        }
        return result.toString();
    }

    private Object bounded(Object value, int maxSize) {
        if (maxSize <= 0 || value == null) return maxSize <= 0 ? null : value;
        try {
            String serialized = JSON.toJSONString(value);
            return serialized.length() <= maxSize ? value : truncate(serialized, maxSize);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String truncate(String value, int maxSize) {
        if (value == null || maxSize <= 0) return maxSize <= 0 ? null : value;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxSize) return value;

        byte[] marker = "...<truncated>".getBytes(StandardCharsets.UTF_8);
        if (maxSize <= marker.length) return new String(marker, 0, maxSize, StandardCharsets.UTF_8);

        int limit = maxSize - marker.length;
        int end = limit;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8) + "...<truncated>";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
