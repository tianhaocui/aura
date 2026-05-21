package io.aura;

import io.aura.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

public final class BeanValidator {

    public static void validate(Object obj) {
        if (obj == null) return;
        Class<?> clazz = obj.getClass();
        if (!clazz.isRecord()) return;

        List<String> errors = new ArrayList<>();
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
                if (error != null) errors.add(error);
            }
        }
        if (!errors.isEmpty()) {
            throw new Validate.ValidationException(String.join("; ", errors));
        }
        if (obj instanceof Validatable v) {
            v.validate();
        }
    }

    private static String check(Annotation ann, String name, Object value) {
        if (ann instanceof NotNull a) {
            if (value == null) return name + ": " + a.message();
        } else if (ann instanceof NotBlank a) {
            if (value == null || (value instanceof String s && s.isBlank())) {
                return name + ": " + a.message();
            }
        } else if (ann instanceof Min a) {
            if (value instanceof Number n && n.longValue() < a.value()) {
                String msg = a.message().isEmpty() ? "must be >= " + a.value() : a.message();
                return name + ": " + msg;
            }
        } else if (ann instanceof Max a) {
            if (value instanceof Number n && n.longValue() > a.value()) {
                String msg = a.message().isEmpty() ? "must be <= " + a.value() : a.message();
                return name + ": " + msg;
            }
        } else if (ann instanceof Size a) {
            if (value instanceof String s) {
                if (s.length() < a.min() || s.length() > a.max()) {
                    String msg = a.message().isEmpty()
                            ? "size must be between " + a.min() + " and " + a.max()
                            : a.message();
                    return name + ": " + msg;
                }
            }
        } else if (ann instanceof Pattern a) {
            if (value instanceof String s && !s.matches(a.value())) {
                String msg = a.message().isEmpty() ? "must match " + a.value() : a.message();
                return name + ": " + msg;
            }
        }
        return null;
    }

    private BeanValidator() {}
}
