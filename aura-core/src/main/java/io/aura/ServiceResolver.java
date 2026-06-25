package io.aura;

import java.lang.reflect.Constructor;
import java.util.*;

class ServiceResolver {

    static List<Object> resolve(List<Class<?>> classes, Map<Class<?>, Object> registry) {
        List<Class<?>> sorted = topologicalSort(classes, registry);
        List<Object> instantiated = new ArrayList<>();
        for (Class<?> clazz : sorted) {
            Object instance = instantiate(clazz, registry);
            registry.put(clazz, instance);
            instantiated.add(instance);
        }
        return instantiated;
    }

    static Object instantiate(Class<?> clazz, Map<Class<?>, Object> registry) {
        Constructor<?>[] constructors = clazz.getConstructors();
        if (constructors.length == 0) {
            throw new IllegalStateException(
                    "[Aura] Cannot create " + clazz.getSimpleName() + ": no public constructor found.");
        }
        if (constructors.length > 1) {
            throw new IllegalStateException(
                    "[Aura] Cannot create " + clazz.getSimpleName() + ": ambiguous — found " +
                            constructors.length + " public constructors. Keep only one.");
        }
        Constructor<?> ctor = constructors[0];
        Class<?>[] paramTypes = ctor.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = findBean(paramTypes[i], registry);
            if (args[i] == null) {
                throw new IllegalStateException(buildMissingBeanMessage(clazz, paramTypes[i], i, registry));
            }
        }
        try {
            return ctor.newInstance(args);
        } catch (Exception e) {
            throw new IllegalStateException("[Aura] Failed to instantiate " + clazz.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    static List<Class<?>> topologicalSort(List<Class<?>> classes, Map<Class<?>, Object> registry) {
        Set<Class<?>> classSet = new LinkedHashSet<>(classes);
        Map<Class<?>, List<Class<?>>> deps = new LinkedHashMap<>();
        for (Class<?> clazz : classes) {
            Constructor<?>[] ctors = clazz.getConstructors();
            List<Class<?>> needed = new ArrayList<>();
            if (ctors.length == 1) {
                for (Class<?> param : ctors[0].getParameterTypes()) {
                    if (classSet.contains(param)) {
                        needed.add(param);
                    } else {
                        for (Class<?> c : classSet) {
                            if (param.isAssignableFrom(c)) {
                                needed.add(c);
                                break;
                            }
                        }
                    }
                }
            }
            deps.put(clazz, needed);
        }

        List<Class<?>> sorted = new ArrayList<>();
        Set<Class<?>> visited = new LinkedHashSet<>();
        Set<Class<?>> visiting = new LinkedHashSet<>();

        for (Class<?> clazz : classes) {
            if (!visited.contains(clazz)) {
                visit(clazz, deps, visited, visiting, sorted);
            }
        }
        return sorted;
    }

    private static void visit(Class<?> node, Map<Class<?>, List<Class<?>>> deps,
                              Set<Class<?>> visited, Set<Class<?>> visiting, List<Class<?>> sorted) {
        if (visiting.contains(node)) {
            List<String> cycle = new ArrayList<>();
            boolean found = false;
            for (Class<?> c : visiting) {
                if (c == node) found = true;
                if (found) cycle.add(c.getSimpleName());
            }
            cycle.add(node.getSimpleName());
            throw new IllegalStateException(
                    "[Aura] Circular dependency detected: " + String.join(" → ", cycle));
        }
        if (visited.contains(node)) return;
        visiting.add(node);
        for (Class<?> dep : deps.getOrDefault(node, List.of())) {
            visit(dep, deps, visited, visiting, sorted);
        }
        visiting.remove(node);
        visited.add(node);
        sorted.add(node);
    }

    private static Object findBean(Class<?> type, Map<Class<?>, Object> registry) {
        Object exact = registry.get(type);
        if (exact != null) return exact;
        Object match = null;
        Class<?> matchKey = null;
        for (Map.Entry<Class<?>, Object> entry : registry.entrySet()) {
            if (type.isAssignableFrom(entry.getKey())) {
                if (match != null) {
                    throw new IllegalStateException(
                            "[Aura] Multiple beans match type " + type.getSimpleName() + ": [" +
                                    matchKey.getSimpleName() + ", " + entry.getKey().getSimpleName() +
                                    "]. Use app.register(\"name\", instance) + @Named to disambiguate.");
                }
                match = entry.getValue();
                matchKey = entry.getKey();
            }
        }
        return match;
    }

    private static String buildMissingBeanMessage(Class<?> target, Class<?> missing, int paramIndex,
                                                  Map<Class<?>, Object> registry) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Aura] Cannot create ").append(target.getSimpleName()).append(":\n");
        sb.append("  Constructor param #").append(paramIndex + 1).append(" requires: ").append(missing.getSimpleName()).append("\n");
        sb.append("  No bean of type ").append(missing.getSimpleName()).append(" is registered.\n");
        sb.append("  Registered beans: ").append(registryNames(registry)).append("\n");
        sb.append("  Hint: add ").append(missing.getSimpleName())
                .append(".class to services() or register an instance with app.register().");
        return sb.toString();
    }

    private static String registryNames(Map<Class<?>, Object> registry) {
        List<String> names = new ArrayList<>();
        for (Class<?> c : registry.keySet()) {
            names.add(c.getSimpleName());
        }
        return names.toString();
    }
}
