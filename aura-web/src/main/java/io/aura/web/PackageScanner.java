package io.aura.web;

import io.aura.annotation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

final class PackageScanner {

    private static final Logger log = LoggerFactory.getLogger(PackageScanner.class);

    static void scan(List<String> packages, Router router) {
        for (String pkg : packages) {
            List<Class<?>> classes = findClasses(pkg);
            for (Class<?> clazz : classes) {
                if (clazz.isAnnotationPresent(Path.class)) {
                    try {
                        Object instance = clazz.getDeclaredConstructor().newInstance();
                        ServiceRegistrar.register(instance, router);
                        log.info("Registered service: {}", clazz.getSimpleName());
                    } catch (Exception e) {
                        log.warn("Failed to instantiate {}: {}", clazz.getName(), e.getMessage());
                    }
                }
            }
        }
    }

    private static List<Class<?>> findClasses(String packageName) {
        List<Class<?>> classes = new ArrayList<>();
        String path = packageName.replace('.', '/');
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = cl.getResources(path);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    scanDirectory(new File(resource.toURI()), packageName, classes);
                }
            }
        } catch (Exception e) {
            log.error("Package scan failed for {}: {}", packageName, e.getMessage());
        }
        return classes;
    }

    private static void scanDirectory(File dir, String packageName, List<Class<?>> classes) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), classes);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                try {
                    classes.add(Class.forName(className));
                } catch (ClassNotFoundException ignored) {}
            }
        }
    }
}
