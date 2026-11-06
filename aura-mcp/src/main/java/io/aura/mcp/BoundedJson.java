package io.aura.mcp;

import java.beans.Introspector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/** Streams JSON into a hard byte budget instead of allocating an unbounded intermediate string. */
final class BoundedJson {
    private static final int MAX_DEPTH = 64;

    private BoundedJson() { }

    static String encode(Object value, int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        try (Writer writer = new OutputStreamWriter(
                new LimitedOutputStream(bytes, maxBytes), StandardCharsets.UTF_8)) {
            writeValue(value, writer, new IdentityHashMap<>(), 0);
        } catch (LimitExceededException exceeded) {
            throw exceeded;
        } catch (IOException error) {
            throw new IllegalStateException("JSON encoding failed", error);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void writeValue(Object value, Writer writer,
                                   IdentityHashMap<Object, Boolean> active, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting exceeds " + MAX_DEPTH);
        if (value == null || value instanceof Optional<?> optional && optional.isEmpty()) {
            writer.write("null");
        } else if (value instanceof Optional<?> optional) {
            writeValue(optional.get(), writer, active, depth + 1);
        } else if (value instanceof CharSequence || value instanceof Character
                || value instanceof Enum<?> || value instanceof TemporalAccessor
                || value instanceof java.util.Date || value instanceof java.util.UUID
                || value instanceof java.net.URI || value instanceof java.net.URL) {
            writeString(value.toString(), writer);
        } else if (value instanceof Boolean) {
            writer.write(value.toString());
        } else if (value instanceof Number number) {
            if (number instanceof Double d && !Double.isFinite(d)
                    || number instanceof Float f && !Float.isFinite(f)) {
                writer.write("null");
            } else {
                writer.write(number.toString());
            }
        } else if (value instanceof byte[] data) {
            writeString(Base64.getEncoder().encodeToString(data), writer);
        } else if (value instanceof Map<?, ?> map) {
            withCycleCheck(value, active, () -> writeMap(map, writer, active, depth));
        } else if (value instanceof Iterable<?> iterable) {
            withCycleCheck(value, active, () -> writeIterable(iterable, writer, active, depth));
        } else if (value.getClass().isArray()) {
            withCycleCheck(value, active, () -> writeArray(value, writer, active, depth));
        } else if (value.getClass().isRecord()) {
            withCycleCheck(value, active, () -> writeRecord(value, writer, active, depth));
        } else {
            withCycleCheck(value, active, () -> writeBean(value, writer, active, depth));
        }
    }

    private static void writeMap(Map<?, ?> map, Writer writer,
                                 IdentityHashMap<Object, Boolean> active, int depth) throws IOException {
        writer.write('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) writer.write(',');
            first = false;
            writeString(String.valueOf(entry.getKey()), writer);
            writer.write(':');
            writeValue(entry.getValue(), writer, active, depth + 1);
        }
        writer.write('}');
    }

    private static void writeIterable(Iterable<?> iterable, Writer writer,
                                      IdentityHashMap<Object, Boolean> active, int depth) throws IOException {
        writer.write('[');
        boolean first = true;
        for (Object item : iterable) {
            if (!first) writer.write(',');
            first = false;
            writeValue(item, writer, active, depth + 1);
        }
        writer.write(']');
    }

    private static void writeArray(Object array, Writer writer,
                                   IdentityHashMap<Object, Boolean> active, int depth) throws IOException {
        writer.write('[');
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            if (i > 0) writer.write(',');
            writeValue(Array.get(array, i), writer, active, depth + 1);
        }
        writer.write(']');
    }

    private static void writeRecord(Object record, Writer writer,
                                    IdentityHashMap<Object, Boolean> active, int depth) throws IOException {
        writer.write('{');
        var components = record.getClass().getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) writer.write(',');
            var component = components[i];
            writeString(component.getName(), writer);
            writer.write(':');
            try {
                writeValue(component.getAccessor().invoke(record), writer, active, depth + 1);
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("Cannot read record component " + component.getName(), error);
            }
        }
        writer.write('}');
    }

    private static void writeBean(Object bean, Writer writer,
                                  IdentityHashMap<Object, Boolean> active, int depth) throws IOException {
        try {
            var properties = Introspector.getBeanInfo(bean.getClass(), Object.class).getPropertyDescriptors();
            if (properties.length == 0) {
                writeString(bean.toString(), writer);
                return;
            }
            java.util.Arrays.sort(properties, java.util.Comparator.comparing(
                    java.beans.PropertyDescriptor::getName));
            writer.write('{');
            for (int i = 0; i < properties.length; i++) {
                if (i > 0) writer.write(',');
                var property = properties[i];
                writeString(property.getName(), writer);
                writer.write(':');
                var getter = property.getReadMethod();
                if (getter == null) writer.write("null");
                else writeValue(getter.invoke(bean), writer, active, depth + 1);
            }
            writer.write('}');
        } catch (ReflectiveOperationException | java.beans.IntrospectionException error) {
            throw new IllegalArgumentException("Cannot encode " + bean.getClass().getName(), error);
        }
    }

    private static void writeString(String value, Writer writer) throws IOException {
        writer.write('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> writer.write("\\\"");
                case '\\' -> writer.write("\\\\");
                case '\b' -> writer.write("\\b");
                case '\f' -> writer.write("\\f");
                case '\n' -> writer.write("\\n");
                case '\r' -> writer.write("\\r");
                case '\t' -> writer.write("\\t");
                default -> {
                    if (c < 0x20) writer.write(String.format("\\u%04x", (int) c));
                    else writer.write(c);
                }
            }
        }
        writer.write('"');
    }

    private static void withCycleCheck(Object value, IdentityHashMap<Object, Boolean> active,
                                       IoRunnable action) throws IOException {
        if (active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Cyclic JSON value: " + value.getClass().getName());
        }
        try {
            action.run();
        } finally {
            active.remove(value);
        }
    }

    @FunctionalInterface
    private interface IoRunnable { void run() throws IOException; }

    static final class LimitExceededException extends RuntimeException {
        private LimitExceededException(int maxBytes) {
            super("JSON output exceeds " + maxBytes + " bytes");
        }
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate;
        private final int limit;
        private int count;

        private LimitedOutputStream(ByteArrayOutputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override public void write(int value) {
            ensureCapacity(1);
            delegate.write(value);
            count++;
        }

        @Override public void write(byte[] bytes, int offset, int length) {
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
            count += length;
        }

        private void ensureCapacity(int length) {
            if (length > limit - count) throw new LimitExceededException(limit);
        }
    }
}
