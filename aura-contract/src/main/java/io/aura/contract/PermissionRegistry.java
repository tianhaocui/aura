package io.aura.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe registry of explicitly declared module permissions. */
public final class PermissionRegistry {
    private final Map<String, Permission> permissions = Collections.synchronizedMap(new LinkedHashMap<>());

    public PermissionRegistry register(String id, String description) {
        Permission permission = new Permission(id, description);
        synchronized (permissions) {
            if (permissions.putIfAbsent(permission.id(), permission) != null) {
                throw new ContractException("AURA_PERMISSION_DUPLICATE", "Permission already registered: " + permission.id());
            }
        }
        return this;
    }

    public boolean contains(String id) {
        return permissions.containsKey(id);
    }

    public List<Permission> list() {
        synchronized (permissions) { return List.copyOf(new ArrayList<>(permissions.values())); }
    }

    public Map<String, Permission> snapshot() {
        synchronized (permissions) { return Map.copyOf(new LinkedHashMap<>(permissions)); }
    }
}
