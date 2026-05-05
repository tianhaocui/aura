package io.aura.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CompiledRoute(String method, String rawPath, Pattern pattern, List<String> paramNames,
                     List<Handler> beforeHandlers, Handler handler,
                     List<Handler> afterHandlers, RouteMeta meta) {

    Map<String, String> match(String path) {
        Matcher m = pattern.matcher(path);
        if (!m.matches()) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            params.put(paramNames.get(i), m.group(i + 1));
        }
        return params;
    }

    static CompiledRoute compile(String method, String prefix, String path,
                                  List<Handler> beforeHandlers, Handler handler,
                                  List<Handler> afterHandlers, RouteMeta meta) {
        String fullPath = prefix + path;
        List<String> paramNames = new ArrayList<>();
        String regex = fullPath.replaceAll("\\{([a-zA-Z_][a-zA-Z0-9_]*)}", "([^/]+)");
        Pattern paramPattern = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");
        Matcher m = paramPattern.matcher(fullPath);
        while (m.find()) {
            paramNames.add(m.group(1));
        }
        return new CompiledRoute(method, fullPath, Pattern.compile("^" + regex + "$"),
                paramNames, beforeHandlers, handler, afterHandlers, meta);
    }

    public record RouteMeta(String description, String returnType, Map<String, String> params) {}
}
