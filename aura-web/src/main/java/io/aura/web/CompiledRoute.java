package io.aura.web;

import io.aura.contract.Route;

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

    Map<String, String> match(String path) {
        Matcher m = pattern.matcher(path);
        if (!m.matches()) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            params.put(paramNames.get(i), m.group(i + 1));
        }
        return params;
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
