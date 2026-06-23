package io.aura.dev;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Child-first (parent-last) ClassLoader that loads classes from its own URLs
 * before delegating to the parent. Framework classes (io.aura.* excluding user code)
 * are still delegated to parent to ensure cross-ClassLoader compatibility.
 */
class ChildFirstClassLoader extends URLClassLoader {

    ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;

            // Framework and JDK classes always delegate to parent
            if (name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("io.aura.") && !isUserPackage(name)
                    || name.startsWith("org.slf4j.")
                    || name.startsWith("com.alibaba.fastjson2.")) {
                return super.loadClass(name, resolve);
            }

            // Try child first for user application classes
            try {
                c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException e) {
                // Fall back to parent
                return super.loadClass(name, resolve);
            }
        }
    }

    private boolean isUserPackage(String name) {
        // io.aura.dev is framework; anything else under io.aura.* that's not
        // core/web/db/mcp is user code living in same package
        return !name.startsWith("io.aura.web.")
                && !name.startsWith("io.aura.db.")
                && !name.startsWith("io.aura.mcp.")
                && !name.startsWith("io.aura.dev.")
                && !name.startsWith("io.aura.annotation.")
                && !isFrameworkCoreClass(name);
    }

    private boolean isFrameworkCoreClass(String name) {
        if (!name.startsWith("io.aura.")) return false;
        // io.aura.Aura, io.aura.AuraPlugin, io.aura.Validate, etc.
        String afterPrefix = name.substring("io.aura.".length());
        return !afterPrefix.contains("."); // top-level io.aura.* classes are framework
    }
}
