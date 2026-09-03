package io.aura;

import io.aura.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.PatternSyntaxException;

public final class BeanValidator {

    private static final int MAX_PATTERN_INPUT_LENGTH = 4096;
    private static final int MAX_PATTERN_LENGTH = 1024;
    private static final ConcurrentHashMap<String, java.util.regex.Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    public static void validate(Object obj) {
        if (obj == null) return;
        Class<?> clazz = obj.getClass();
        if (!clazz.isRecord()) return;

        List<Validate.FieldError> errors = new ArrayList<>();
        for (RecordComponent rc : clazz.getRecordComponents()) {
            Object value;
            try {
                value = rc.getAccessor().invoke(obj);
            } catch (Exception e) {
                continue;
            }
            String name = rc.getName();
            for (Annotation ann : rc.getAnnotations()) {
                String error = check(ann, name, value);
                if (error != null) errors.add(new Validate.FieldError(name, error));
            }
        }
        if (!errors.isEmpty()) {
            throw new Validate.ValidationException(errors);
        }
        if (obj instanceof Validatable v) {
            v.validate();
        }
    }

    private static String check(Annotation ann, String name, Object value) {
        if (ann instanceof NotNull a) {
            if (value == null) return a.message();
        } else if (ann instanceof NotBlank a) {
            if (value == null || (value instanceof String s && s.isBlank())) {
                return a.message();
            }
        } else if (ann instanceof Min a) {
            if (value instanceof Number n && n.longValue() < a.value()) {
                return a.message().isEmpty() ? "must be >= " + a.value() : a.message();
            }
        } else if (ann instanceof Max a) {
            if (value instanceof Number n && n.longValue() > a.value()) {
                return a.message().isEmpty() ? "must be <= " + a.value() : a.message();
            }
        } else if (ann instanceof Size a) {
            if (value instanceof String s) {
                if (s.length() < a.min() || s.length() > a.max()) {
                    return a.message().isEmpty()
                            ? "size must be between " + a.min() + " and " + a.max()
                            : a.message();
                }
            }
        } else if (ann instanceof Pattern a) {
            if (value instanceof String s) {
                if (s.length() > MAX_PATTERN_INPUT_LENGTH) {
                    return a.message().isEmpty() ? "length exceeds validation limit" : a.message();
                }
                if (a.value() == null || a.value().length() > MAX_PATTERN_LENGTH) {
                    return a.message().isEmpty() ? "invalid validation pattern" : a.message();
                }
                try {
                    java.util.regex.Pattern pattern = PATTERN_CACHE.get(a.value());
                    if (pattern == null) {
                        java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(a.value());
                        if (isPotentiallyUnsafe(a.value())) {
                            return a.message().isEmpty() ? "unsafe validation pattern" : a.message();
                        }
                        java.util.regex.Pattern cached = PATTERN_CACHE.putIfAbsent(a.value(), compiled);
                        pattern = cached == null ? compiled : cached;
                    }
                    if (!pattern.matcher(s).matches()) {
                        return a.message().isEmpty() ? "must match " + a.value() : a.message();
                    }
                } catch (PatternSyntaxException e) {
                    return a.message().isEmpty() ? "invalid validation pattern" : a.message();
                }
            }
        }
        return null;
    }

    private static boolean isPotentiallyUnsafe(String regex) {
        int characterClassDepth = 0;
        int variableQuantifiers = 0;
        boolean canQuantify = false;

        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);

            if (characterClassDepth > 0) {
                if (c == '\\') {
                    i++;
                } else if (c == '[') {
                    characterClassDepth++;
                } else if (c == ']') {
                    characterClassDepth--;
                    if (characterClassDepth == 0) {
                        canQuantify = true;
                    }
                }
                continue;
            }

            if (c == '\\') {
                char escaped = regex.charAt(++i);
                if ((escaped >= '1' && escaped <= '9') || escaped == 'k'
                        || escaped == 'Q' || escaped == 'E' || escaped == 'R' || escaped == 'X') {
                    return true;
                }
                if ((escaped == 'p' || escaped == 'P') && i + 1 < regex.length()
                        && regex.charAt(i + 1) == '{') {
                    i = regex.indexOf('}', i + 2);
                }
                canQuantify = true;
            } else if (c == '[') {
                characterClassDepth = 1;
                canQuantify = false;
            } else if (c == '(' || c == ')' || c == '|') {
                return true;
            } else if (c == '*' || c == '+' || c == '?') {
                if (!canQuantify || ++variableQuantifiers > 1) {
                    return true;
                }
                canQuantify = false;
            } else if (c == '{') {
                Quantifier quantifier = parseQuantifier(regex, i);
                if (!canQuantify || quantifier == null) {
                    return true;
                }
                if (quantifier.variable() && ++variableQuantifiers > 1) {
                    return true;
                }
                i = quantifier.end();
                canQuantify = false;
            } else if (c == '^' || c == '$') {
                canQuantify = false;
            } else {
                canQuantify = true;
            }
        }
        return false;
    }

    private static Quantifier parseQuantifier(String regex, int start) {
        int end = regex.indexOf('}', start + 1);
        if (end < 0) {
            return null;
        }

        String bounds = regex.substring(start + 1, end);
        int comma = bounds.indexOf(',');
        if (comma < 0) {
            return isAllowedBound(bounds) ? new Quantifier(end, false) : null;
        }
        if (comma != bounds.lastIndexOf(',') || !isAllowedBound(bounds.substring(0, comma))) {
            return null;
        }

        String maximum = bounds.substring(comma + 1);
        if (!maximum.isEmpty() && !isAllowedBound(maximum)) {
            return null;
        }
        return new Quantifier(end, !bounds.substring(0, comma).equals(maximum));
    }

    private static boolean isAllowedBound(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int bound = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            bound = bound * 10 + (c - '0');
            if (bound > MAX_PATTERN_INPUT_LENGTH) {
                return false;
            }
        }
        return true;
    }

    private record Quantifier(int end, boolean variable) {}

    private BeanValidator() {}
}
