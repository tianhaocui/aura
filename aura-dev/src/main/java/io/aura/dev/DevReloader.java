package io.aura.dev;

import io.aura.Aura;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class DevReloader {

    private static final Logger log = LoggerFactory.getLogger(DevReloader.class);

    private final Aura app;
    private final String mainClass;
    private final Path sourceDir;
    private final Path outputDir;
    private final String classpath;
    private volatile URLClassLoader userClassLoader;

    public DevReloader(Aura app, String mainClass) {
        this.app = app;
        this.mainClass = mainClass;
        this.sourceDir = Paths.get("src/main/java");
        this.outputDir = Paths.get("target/classes");
        this.classpath = System.getProperty("java.class.path");
    }

    public void start() {
        Aura.setReloadInstance(app);
        Thread watcher = new Thread(this::watchLoop, "aura-dev-watcher");
        watcher.setDaemon(true);
        watcher.start();
        log.info("[aura-dev] watching {} for changes", sourceDir.toAbsolutePath());
    }

    private void watchLoop() {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            registerRecursive(sourceDir, ws);
            while (true) {
                WatchKey key = ws.take();
                Thread.sleep(200);
                List<Path> changed = collectChangedFiles(key);
                key.reset();
                WatchKey extra;
                while ((extra = ws.poll()) != null) {
                    changed.addAll(collectChangedFiles(extra));
                    extra.reset();
                }
                if (!changed.isEmpty()) {
                    reload(changed);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[aura-dev] watcher error", e);
        }
    }

    private void reload(List<Path> changedFiles) {
        long t0 = System.currentTimeMillis();
        try {
            boolean compiled = compile(changedFiles);
            if (!compiled) {
                log.warn("[aura-dev] compilation failed, skipping reload");
                return;
            }

            app.fireReloadCleanup();

            if (userClassLoader != null) {
                userClassLoader.close();
            }

            userClassLoader = new URLClassLoader(
                    new URL[]{outputDir.toUri().toURL()},
                    getClass().getClassLoader()
            );

            Class<?> mainCls = userClassLoader.loadClass(mainClass);
            Method main = mainCls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[]{});

            long elapsed = System.currentTimeMillis() - t0;
            log.info("[aura-dev] reloaded in {}ms ({} files)", elapsed, changedFiles.size());
        } catch (Exception e) {
            log.error("[aura-dev] reload failed: {}", e.getMessage());
        }
    }

    private boolean compile(List<Path> files) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            var sources = fm.getJavaFileObjectsFromPaths(files);
            var options = List.of(
                    "-cp", classpath,
                    "-d", outputDir.toString(),
                    "-parameters",
                    "-source", "17",
                    "-target", "17"
            );
            boolean success = compiler.getTask(null, fm, diagnostics, options, null, sources).call();
            if (!success) {
                for (var d : diagnostics.getDiagnostics()) {
                    log.error("[aura-dev] {}:{} {}",
                            d.getSource() != null ? d.getSource().getName() : "?",
                            d.getLineNumber(), d.getMessage(null));
                }
            }
            return success;
        } catch (Exception e) {
            log.error("[aura-dev] compile error", e);
            return false;
        }
    }

    private void registerRecursive(Path root, WatchService ws) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private List<Path> collectChangedFiles(WatchKey key) {
        List<Path> files = new ArrayList<>();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
            Path changed = ((Path) key.watchable()).resolve((Path) event.context());
            if (changed.toString().endsWith(".java")) {
                files.add(changed);
            }
        }
        return files;
    }
}