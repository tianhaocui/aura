package io.aura.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkflowRegistry {
    private final Map<String, WorkflowDefinition<?>> definitions = new ConcurrentHashMap<>();

    public <C> WorkflowRegistry register(WorkflowDefinition<C> definition) {
        Objects.requireNonNull(definition, "definition");
        if (definitions.putIfAbsent(definition.id(), definition) != null) {
            throw new WorkflowException(WorkflowException.REGISTRATION_CONFLICT,
                    "Workflow already registered: " + definition.id());
        }
        return this;
    }

    public boolean contains(String id) { return definitions.containsKey(id); }
    public WorkflowDefinition<?> get(String id) {
        WorkflowDefinition<?> definition = definitions.get(id);
        if (definition == null) throw new WorkflowException(WorkflowException.UNKNOWN_WORKFLOW, "Unknown workflow: " + id);
        return definition;
    }
    public Map<String, WorkflowDefinition<?>> snapshot() { return Map.copyOf(new LinkedHashMap<>(definitions)); }
}
