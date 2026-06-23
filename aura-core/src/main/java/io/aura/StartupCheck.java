package io.aura;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.List;

class StartupCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);

    static void checkParameterNames(List<Object> services) {
        for (Object service : services) {
            for (var m : service.getClass().getDeclaredMethods()) {
                if (m.isSynthetic() || m.getParameterCount() == 0) continue;
                if (Modifier.isPublic(m.getModifiers())
                        && m.getParameters()[0].getName().startsWith("arg")) {
                    log.warn("[Aura] Service {} method {}() has synthetic parameter names. " +
                            "Add -parameters to javac options for route parameter binding to work.",
                            service.getClass().getSimpleName(), m.getName());
                    return;
                }
            }
        }
    }
}
