package io.aura.dev;

import io.aura.Aura;
import io.aura.AuraPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.ToolProvider;

public class DevModePlugin implements AuraPlugin {

    private static final Logger log = LoggerFactory.getLogger(DevModePlugin.class);

    @Override
    public void install(Aura app) {
        if (!Aura.isDevMode()) return;

        if (ToolProvider.getSystemJavaCompiler() == null) {
            log.error("[aura-dev] JDK required for dev mode (javax.tools.JavaCompiler not found)");
            log.error("[aura-dev] Current java.home: {}", System.getProperty("java.home"));
            log.error("[aura-dev] Fix: run with JDK, not JRE");
            throw new IllegalStateException("[aura-dev] requires JDK for hot-reload");
        }

        String mainClass = detectMainClass();
        if (mainClass == null) {
            log.warn("[aura-dev] Could not detect main class, hot-reload disabled");
            return;
        }

        DevReloader reloader = new DevReloader(app, mainClass);
        reloader.start();
    }

    private String detectMainClass() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = stack.length - 1; i >= 0; i--) {
            if ("main".equals(stack[i].getMethodName())) {
                return stack[i].getClassName();
            }
        }
        return null;
    }
}
