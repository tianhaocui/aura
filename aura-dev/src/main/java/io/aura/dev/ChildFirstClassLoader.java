package io.aura.dev;

import java.net.URL;
import java.net.URLClassLoader;

class ChildFirstClassLoader extends URLClassLoader {

    private final String userPackagePrefix;

    ChildFirstClassLoader(URL[] urls, ClassLoader parent, String userPackagePrefix) {
        super(urls, parent);
        this.userPackagePrefix = userPackagePrefix;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;

            if (!userPackagePrefix.isEmpty() && name.startsWith(userPackagePrefix)) {
                try {
                    c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                } catch (ClassNotFoundException e) {
                    return super.loadClass(name, resolve);
                }
            }

            return super.loadClass(name, resolve);
        }
    }
}
