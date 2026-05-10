package io.aura.web;


import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public final class LambdaHandler implements BaseHandler {

    private final Object lambda;
    private final Method samMethod;
    private final boolean hasReturn;

    public LambdaHandler(Object lambda) {
        this.lambda = lambda;
        this.samMethod = findSamMethod(lambda);
        this.hasReturn = samMethod.getReturnType() != void.class;
    }

    @Override
    public void handle(BaseContext ctx) throws Exception {
        Parameter[] params = samMethod.getParameters();
        Object[] args = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();
            String name = params[i].getName();

            if (type == Context.class || type == BaseContext.class) {
                args[i] = ctx;
            } else if (type == String.class) {
                String val = ctx.path(name);
                args[i] = val != null ? val : ctx.query(name);
            } else if (type == int.class) {
                String val = ctx.path(name);
                if (val == null) val = ctx.query(name);
                args[i] = val != null ? Integer.parseInt(val) : 0;
            } else if (type == Integer.class) {
                String val = ctx.path(name);
                if (val == null) val = ctx.query(name);
                args[i] = val != null ? Integer.parseInt(val) : null;
            } else if (type == long.class) {
                String val = ctx.path(name);
                if (val == null) val = ctx.query(name);
                args[i] = val != null ? Long.parseLong(val) : 0L;
            } else if (type == Long.class) {
                String val = ctx.path(name);
                if (val == null) val = ctx.query(name);
                args[i] = val != null ? Long.parseLong(val) : null;
            } else if (type == boolean.class) {
                String val = ctx.path(name);
                if (val == null) val = ctx.query(name);
                args[i] = "true".equalsIgnoreCase(val);
            } else if (type == Boolean.class) {
                String val = ctx.path(name);
                if (val == null) val = ctx.query(name);
                args[i] = val != null ? "true".equalsIgnoreCase(val) : null;
            } else if (type.isRecord() || TypeUtil.isPojo(type)) {
                args[i] = ctx.body(type);
            } else {
                String val = ctx.path(name);
                args[i] = val != null ? val : ctx.query(name);
            }
        }

        Object result = samMethod.invoke(lambda, args);
        if (hasReturn && result != null) {
            if (result instanceof String s) {
                ctx.text(s);
            } else {
                ctx.json(result);
            }
        }
    }

    Method samMethod() { return samMethod; }

    private static Method findSamMethod(Object lambda) {
        Class<?> clazz = lambda.getClass();
        for (Class<?> iface : clazz.getInterfaces()) {
            for (Method m : iface.getMethods()) {
                if (!m.isDefault() && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    try {
                        Method impl = clazz.getMethod(m.getName(), m.getParameterTypes());
                        impl.setAccessible(true);
                        return impl;
                    } catch (NoSuchMethodException e) {
                        // continue
                    }
                }
            }
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.isSynthetic() && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new IllegalArgumentException("Cannot find SAM method on " + clazz.getName());
    }
}
