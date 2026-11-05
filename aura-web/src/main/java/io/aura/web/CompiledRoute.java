package io.aura.web;

import io.aura.port.BaseHandler;
import io.aura.contract.Route;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CompiledRoute(String method, String rawPath, Pattern pattern, List<String> paramNames,
                     List<BaseHandler> beforeHandlers, BaseHandler handler,
                     List<BaseHandler> afterHandlers, Route contract,
                     Map<String, String> paramDescriptions) {

    Map<String, String> match(String rawPath) {
        String path = decodePath(rawPath);
        if (path == null) return null;
        Matcher m = pattern.matcher(path);
        if (!m.matches()) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            params.put(paramNames.get(i), m.group(i + 1));
        }
        return params;
    }

    /**
     * Decodes each path segment exactly once before matching. The decoded form is what both a
     * static route and a handler were promised; decoding per segment keeps an encoded slash from
     * turning one wildcard into two segments. A segment that decodes to a slash, a backslash, a
     * NUL, or malformed UTF-8 is refused as no-match rather than handed to a handler.
     */
    static String decodePath(String rawPath) {
        if (rawPath == null || rawPath.indexOf('%') < 0) return rawPath;
        String[] segments = rawPath.split("/", -1);
        StringBuilder decoded = new StringBuilder(rawPath.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) decoded.append('/');
            String segment = decodeSegment(segments[i]);
            if (segment == null) return null;
            decoded.append(segment);
        }
        return decoded.toString();
    }

    private static String decodeSegment(String segment) {
        if (segment.indexOf('%') < 0) return unsafeSegment(segment) ? null : segment;
        StringBuilder text = new StringBuilder(segment.length());
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c != '%') {
                if (pending.size() > 0) {
                    String decoded = decodeUtf8(pending);
                    if (decoded == null) return null;
                    text.append(decoded);
                    pending.reset();
                }
                text.append(c);
                continue;
            }
            int high = i + 2 < segment.length() ? Character.digit(segment.charAt(i + 1), 16) : -1;
            int low = high >= 0 ? Character.digit(segment.charAt(i + 2), 16) : -1;
            if (high < 0 || low < 0) return unsafeSegment(segment) ? null : segment;
            pending.write((high << 4) | low);
            i += 2;
        }
        if (pending.size() > 0) {
            String decoded = decodeUtf8(pending);
            if (decoded == null) return null;
            text.append(decoded);
        }
        String decoded = text.toString();
        return unsafeSegment(decoded) ? null : decoded;
    }

    private static boolean unsafeSegment(String segment) {
        return segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0;
    }

    private static String decodeUtf8(ByteArrayOutputStream bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException invalid) {
            return null;
        }
    }

    static CompiledRoute compile(String prefix, Route declared,
                                  List<BaseHandler> beforeHandlers, BaseHandler handler,
                                  List<BaseHandler> afterHandlers,
                                  Map<String, String> paramDescriptions) {
        String fullPath = prefix + declared.path();
        Route contract = declared.withPath(fullPath);
        List<String> paramNames = new ArrayList<>();
        StringBuilder regexBuilder = new StringBuilder();
        Pattern paramPattern = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");
        Matcher m = paramPattern.matcher(fullPath);
        int last = 0;
        while (m.find()) {
            regexBuilder.append(Pattern.quote(fullPath.substring(last, m.start())));
            regexBuilder.append("([^/]+)");
            paramNames.add(m.group(1));
            last = m.end();
        }
        regexBuilder.append(Pattern.quote(fullPath.substring(last)));
        String regex = regexBuilder.toString();
        return new CompiledRoute(contract.method(), fullPath, Pattern.compile("^" + regex + "$"),
                paramNames, beforeHandlers, handler, afterHandlers, contract,
                paramDescriptions == null ? Map.of() : Map.copyOf(paramDescriptions));
    }
}
