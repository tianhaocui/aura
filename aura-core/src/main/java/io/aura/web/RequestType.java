package io.aura.web;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;

/** Creates immutable generic request types without coupling the core to a JSON library. */
public final class RequestType {
    private RequestType() { }

    public static Type parameterized(Class<?> rawType, Type... arguments) {
        if (rawType == null) throw new IllegalArgumentException("rawType must not be null");
        if (arguments == null || arguments.length == 0) {
            throw new IllegalArgumentException("generic arguments must not be empty");
        }
        return new Parameterized(rawType, arguments);
    }

    private static final class Parameterized implements ParameterizedType {
        private final Class<?> rawType;
        private final Type[] arguments;

        private Parameterized(Class<?> rawType, Type[] arguments) {
            this.rawType = rawType;
            arguments = arguments.clone();
            for (Type argument : arguments) {
                if (argument == null) throw new IllegalArgumentException("generic argument must not be null");
            }
            this.arguments = arguments;
        }

        @Override public Type[] getActualTypeArguments() { return arguments.clone(); }
        @Override public Type getRawType() { return rawType; }
        @Override public Type getOwnerType() { return null; }
        @Override public String getTypeName() {
            return rawType.getTypeName() + "<" + Arrays.stream(arguments)
                    .map(Type::getTypeName).reduce((left, right) -> left + "," + right).orElse("") + ">";
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof ParameterizedType value)) return false;
            return rawType.equals(value.getRawType())
                    && java.util.Objects.equals(getOwnerType(), value.getOwnerType())
                    && Arrays.equals(arguments, value.getActualTypeArguments());
        }

        @Override public int hashCode() {
            return Arrays.hashCode(arguments) ^ java.util.Objects.hashCode(rawType)
                    ^ java.util.Objects.hashCode(getOwnerType());
        }
    }
}
