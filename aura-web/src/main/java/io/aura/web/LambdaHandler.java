package io.aura.web;


import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public final class LambdaHandler implements BaseHandler {

    private final Object lambda;
    private final Method samMethod;
    private final boolean hasReturn;
    private final ParamResolver.Binder[] binders;

    public LambdaHandler(Object lambda) {
        this.lambda = lambda;
        this.samMethod = findSamMethod(lambda);
        this.hasReturn = samMethod.getReturnType() != void.class;
        Parameter[] params = samMethod.getParameters();
        this.binders = new ParamResolver.Binder[params.length];
        for (int i = 0; i < params.length; i++) {
            binders[i] = ParamResolver.create(params[i]);
        }
    }

    @Override
    public void handle(BaseContext ctx) throws Exception {
        Object[] args = new Object[binders.length];
        for (int i = 0; i < binders.length; i++) {
            args[i] = binders[i].resolve(ctx);
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
