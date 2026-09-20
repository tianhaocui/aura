package io.aura.db;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;

/** Loads {@code *.sql} migrations from a classpath directory. */
public final class ClasspathSqlMigrationSource implements SqlMigrationSource {
    private final String location;
    private final ClassLoader classLoader;

    public ClasspathSqlMigrationSource(String location) {
        this(location, Thread.currentThread().getContextClassLoader());
    }

    public ClasspathSqlMigrationSource(String location, ClassLoader classLoader) {
        Objects.requireNonNull(location, "location");
        if (location.isBlank()) throw new IllegalArgumentException("location must not be blank");
        this.location = location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
        if (this.location.isBlank()) throw new IllegalArgumentException("location must not be blank");
        this.classLoader = classLoader == null ? ClasspathSqlMigrationSource.class.getClassLoader() : classLoader;
    }

    @Override
    public List<SqlMigration> migrations() {
        String path = location.startsWith("/") ? location.substring(1) : location;
        try {
            Enumeration<URL> resources = classLoader.getResources(path);
            List<SqlMigration> result = new ArrayList<>();
            boolean found = false;
            while (resources.hasMoreElements()) {
                found = true;
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    Path dir = Path.of(resource.toURI());
                    try (var files = Files.list(dir)) {
                        files.filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().endsWith(".sql"))
                                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                                .forEach(p -> result.add(readMigration(p.getFileName().toString(), readFile(p))));
                    }
                } else if ("jar".equals(resource.getProtocol())) {
                    JarURLConnection connection = (JarURLConnection) resource.openConnection();
                    Enumeration<JarEntry> entries = connection.getJarFile().entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (!entry.isDirectory() && entry.getName().startsWith(path + "/") && entry.getName().endsWith(".sql")) {
                            try (InputStream in = classLoader.getResourceAsStream(entry.getName())) {
                                result.add(readMigration(entry.getName().substring(entry.getName().lastIndexOf('/') + 1),
                                        new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                            }
                        }
                    }
                }
            }
            if (!found) throw new MigrationException("Classpath migration location not found: " + location);
            return result;
        } catch (MigrationException e) {
            throw e;
        } catch (Exception e) {
            throw new MigrationException("Cannot load classpath migrations from " + location, e);
        }
    }

    private static SqlMigration readMigration(String fileName, String sql) {
        int underscore = fileName.indexOf('_');
        if (underscore <= 0 || !fileName.endsWith(".sql")) {
            throw new MigrationException("Migration filename must be <version>_<name>.sql: " + fileName);
        }
        String version = fileName.substring(0, underscore);
        String name = fileName.substring(underscore + 1, fileName.length() - 4);
        return new SqlMigration(version, name, sql);
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new MigrationException("Cannot read migration " + path, e);
        }
    }
}
