package io.aura;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.List;

class StartupCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);

    static void checkParameterNames(List<Object> services) {
        checkParameterNames(services, false);
    }

    static void requireParameterNames(List<Object> services) {
        checkParameterNames(services, true);
    }

    private static void checkParameterNames(List<Object> services, boolean fail) {
        for (Object service : services) {
            for (var m : service.getClass().getDeclaredMethods()) {
                if (m.isSynthetic() || m.getParameterCount() == 0) continue;
                if (Modifier.isPublic(m.getModifiers())
                        && m.getParameters()[0].getName().startsWith("arg")) {
                    String message = "Service " + service.getClass().getName() + " method " + m.getName()
                            + " has synthetic parameter names. Add -parameters to javac options.";
                    if (fail) {
                        throw new ModuleException("AURA_PARAMETER_NAMES_MISSING", "runtime", message);
                    }
                    log.warn("[Aura] {} Route parameter binding requires the -parameters compiler option.", message);
                    return;
                }
            }
        }
    }
}
