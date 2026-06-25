package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.BeanValidator;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public final class MethodRefHandler implements BaseHandler {

    private final Object target;
    private final Method method;
    private final ParamResolver.Binder[] binders;
    private final boolean hasReturnValue;

    MethodRefHandler(Object target, String methodName) {
        this.target = target;
        this.method = findMethod(target.getClass(), methodName);
        this.method.setAccessible(true);
        this.hasReturnValue = method.getReturnType() != void.class;

        Parameter[] params = method.getParameters();
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
        Object result = method.invoke(target, args);
        if (!hasReturnValue) return;
        if (ctx instanceof Context c && c.isResponseStarted()) return;
        if (ctx instanceof Context c && c.app() != null && c.app().resultWrapper() != null) {
            ctx.json(c.app().resultWrapper().apply(result));
        } else if (result != null) {
            ctx.json(result);
        }
    }

    public Method resolvedMethod() {
        return method;
    }

    Object target() {
        return target;
    }

    public Object invokeWithArgs(java.util.Map<String, Object> args) throws Exception {
        Parameter[] params = method.getParameters();
        Object[] resolved = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            if (p.getType() == Context.class) {
                resolved[i] = null;
                continue;
            }
            Object val = args.get(p.getName());
            resolved[i] = coerce(val, p.getType());
        }
        return method.invoke(target, resolved);
    }

    private static Object coerce(Object val, Class<?> type) {
        if (val == null) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == boolean.class) return false;
            return null;
        }
        if (type.isInstance(val)) return val;
        String s = val.toString();
        if (type == int.class || type == Integer.class) return Integer.parseInt(s);
        if (type == long.class || type == Long.class) return Long.parseLong(s);
        if (type == double.class || type == Double.class) return Double.parseDouble(s);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(s);
        if (type == String.class) return s;
        if (type.isRecord() || TypeUtil.isPojo(type)) {
            return com.alibaba.fastjson2.JSON.parseObject(
                    com.alibaba.fastjson2.JSON.toJSONString(val), type);
        }
        return val;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        Method found = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                if (found != null) throw new IllegalArgumentException(
                        "Ambiguous method '" + name + "' in " + clazz.getName() + ". Use unique method names.");
                found = m;
            }
        }
        if (found == null) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name)) {
                    if (found != null) throw new IllegalArgumentException(
                            "Ambiguous method '" + name + "' in " + clazz.getName());
                    found = m;
                }
            }
        }
        if (found == null) throw new IllegalArgumentException(
                "No method '" + name + "' found in " + clazz.getName());
        return found;
    }
}
