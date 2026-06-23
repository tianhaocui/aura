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
            if (value instanceof String s && !s.matches(a.value())) {
                return a.message().isEmpty() ? "must match " + a.value() : a.message();
            }
        }
        return null;
    }

    private BeanValidator() {}
}
