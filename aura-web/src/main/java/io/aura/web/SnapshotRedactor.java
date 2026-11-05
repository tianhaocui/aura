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
    /** Fuzzy fragments; a header whose lowercased name contains any of these is treated as sensitive. */
    private static final List<String> SENSITIVE_HEADER_FRAGMENTS = List.of(
            "authorization", "cookie", "token", "secret", "key", "credential", "bearer", "apikey");
    /**
     * Absolute filesystem paths (unix two-or-more segments, windows drive paths) leaking through
     * stack traces. A leading `:` is allowed before the path so a {@code file:/home/deploy/secret}
     * or {@code classpath:/config/prod.yml} URL is scrubbed too -- the URL pass has already turned
     * real network URLs into a hash, so what remains before a slash is a local-file scheme, not a
     * host worth keeping.
     */
    private static final Pattern ABSOLUTE_PATH_PATTERN =
            Pattern.compile("(?<![\\w./-])(?:/[A-Za-z0-9._~-]+){2,}");
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("[A-Za-z]:\\\\(?:[^\\\\\\s\"'()]+)(?:\\\\[^\\\\\\s\"'()]+)*\\\\?");
    private static final Pattern MULTIPART_PART =
            Pattern.compile("(?s)(--[^\\r\\n]+\\r?\\n)(.*?)(?=\\r?\\n--|\\z)");
    private static final Pattern PART_NAME =
            Pattern.compile("(?i)\\bname\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^;\\r\\n]+))");
    private final SnapshotOptions options;

    SnapshotRedactor(SnapshotOptions options) {
        this.options = options;
    }

    Map<String, String> headers(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) return result;
        for (var entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key != null && isSensitiveHeader(key)) {
                result.put(key, "<redacted>");
            } else {
                result.put(key, truncate(redactText(entry.getValue()), options.maxBodySize()));
            }
        }
        return result;
    }

    boolean isSensitiveHeader(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (options.redactHeaders().contains(normalized)) return true;
        for (String fragment : SENSITIVE_HEADER_FRAGMENTS) {
            if (normalized.contains(fragment)) return true;
        }
        return false;
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

    /**
     * Redacts a recorded stack trace. The frames themselves are kept -- an application
     * {@code class.method} is the first thing a reader needs to diagnose a failure, and it is not
     * a secret -- while every absolute filesystem path and every configured field/URL secret is
     * removed, then the size cap is re-applied.
     */
    String stackText(String value) {
        if (value == null) return null;
        String sanitized = redactText(value);
        sanitized = scrubMatches(ABSOLUTE_PATH_PATTERN.matcher(sanitized), "<redacted-path>");
        sanitized = scrubMatches(WINDOWS_PATH_PATTERN.matcher(sanitized), "<redacted-path>");
        return truncate(sanitized, options.maxStackTraceSize());
    }

    private static String scrubMatches(Matcher matcher, String replacement) {
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
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
        String result = redactMultipart(text);
        for (String field : options.redactFields()) {
            String name = java.util.regex.Pattern.quote(field);
            // A JSON value ends at the first *unescaped* quote, so the old [^"]* cut a secret
            // holding an escaped quote in half and left the rest of it in the snapshot.
            result = result.replaceAll("(?i)(\"" + name + "\"\\s*:\\s*\")(?:[^\"\\\\]|\\\\.)*(\")",
                    "$1<redacted>$2");
            // A body that did not parse as JSON is still a body: XML elements, XML attributes and
            // `field: value` lines carry the same secrets and have no key tree for the walk above.
            result = result.replaceAll("(?is)(<" + name + "(?:\\s[^<>]*)?>)[^<]*(</" + name + "\\s*>)",
                    "$1<redacted>$2");
            result = result.replaceAll("(?i)(" + name + "[ \\t]*=[ \\t]*\")([^\"]*)(\")",
                    "$1<redacted>$3");
            // Quoted values are handled above, so this pass stops at markup as well as at the
            // delimiters of a query string -- otherwise it swallows the rest of the document.
            result = result.replaceAll("(?i)(" + name + "=)[^&\\s<>\"]+", "$1<redacted>");
            result = result.replaceAll("(?im)^([ \\t]*" + name + "[ \\t]*:[ \\t]*)(.*)$",
                    "$1<redacted>");
        }
        return result;
    }

    /**
     * A multipart part names its field in a header line and puts the value after a blank line, so
     * no {@code field=value} or {@code "field":"value"} pass can see them together. Each part is
     * checked by name and its value replaced as one unit; the boundary and the other parts keep
     * their shape so the snapshot still shows what was sent.
     */
    private String redactMultipart(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("content-disposition:") || !lower.contains("name=")) return text;
        Matcher part = MULTIPART_PART.matcher(text);
        StringBuffer out = new StringBuffer();
        while (part.find()) {
            String headersAndBody = part.group(2);
            int separator = headersAndBody.indexOf("\r\n\r\n");
            int separatorLength = 4;
            if (separator < 0) {
                separator = headersAndBody.indexOf("\n\n");
                separatorLength = 2;
            }
            if (separator < 0 || !hasSensitivePartName(headersAndBody.substring(0, separator))) {
                part.appendReplacement(out, Matcher.quoteReplacement(part.group()));
                continue;
            }
            String replacement = part.group(1)
                    + headersAndBody.substring(0, separator)
                    + headersAndBody.substring(separator, separator + separatorLength)
                    + "<redacted>";
            part.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        part.appendTail(out);
        return out.toString();
    }

    private boolean hasSensitivePartName(String headers) {
        Matcher name = PART_NAME.matcher(headers);
        while (name.find()) {
            String value = name.group(1) != null ? name.group(1)
                    : name.group(2) != null ? name.group(2) : name.group(3);
            if (value != null && isSensitiveField(value.trim())) return true;
        }
        return false;
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
