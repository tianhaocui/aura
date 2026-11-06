package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Set;

/**
 * A typed reader for the MCP wire documents.
 *
 * <p>Every tool schema in this project is produced as a {@code Map<String, Object>}, and reading
 * one back with an unchecked cast means a renamed or missing key arrives as {@code null} for the
 * next assertion to compare against something else. Reading it through these accessors makes the
 * key itself the subject: a key that moved throws with the keys the document actually has.
 *
 * <p>Test-only by design -- {@code tools/list} has no consumer that needs to parse it, because
 * Aura is the one writing it.
 */
final class McpWire {

    private final JSONObject json;

    private McpWire(JSONObject json) {
        this.json = json;
    }

    /** Reads a produced document -- {@code buildSchema()}, a JSON-RPC result, a schema fragment. */
    static McpWire of(Object document) {
        return new McpWire(JSON.parseObject(JSON.toJSONString(document)));
    }

    List<Tool> tools() {
        JSONArray tools = json.getJSONArray("tools");
        if (tools == null) {
            throw new AssertionError("the document has no tools array: " + json);
        }
        return tools.toList(JSONObject.class).stream().map(Tool::new).toList();
    }

    Tool tool(String name) {
        return tools().stream().filter(tool -> name.equals(tool.name())).findFirst()
                .orElseThrow(() -> new AssertionError("no tool named " + name + "; the document has "
                        + tools().stream().map(Tool::name).toList()));
    }

    /** The content blocks of a {@code tools/call} result. */
    List<Property> contentBlocks() {
        JSONArray content = json.getJSONArray("content");
        if (content == null) {
            throw new AssertionError("the result has no content array: " + json);
        }
        return content.toList(JSONObject.class).stream().map(Property::new).toList();
    }

    Set<String> keys() { return json.keySet(); }

    /** One nested object of the document -- a result's {@code structuredContent}, say. */
    Property object(String key) {
        JSONObject value = json.getJSONObject(key);
        if (value == null) {
            throw new AssertionError("the document has no " + key + ": " + json);
        }
        return new Property(value);
    }

    /** The document itself read as a JSON Schema object, as {@code McpUtil.inputSchema} returns. */
    Schema asSchema() {
        return new Schema(json);
    }

    static final class Tool {
        private final JSONObject json;

        private Tool(JSONObject json) {
            this.json = json;
        }

        String name() { return required("name"); }
        String description() { return json.getString("description"); }
        String permission() { return json.getString("x-aura-permission"); }
        boolean authenticated() { return json.getBooleanValue("x-aura-authenticated"); }

        /** The tool entry's own keys, so a promise that a key stays absent is an assertion. */
        Set<String> keys() { return json.keySet(); }

        Schema inputSchema() {
            JSONObject inputSchema = json.getJSONObject("inputSchema");
            if (inputSchema == null) {
                throw new AssertionError("tool " + name() + " has no inputSchema: " + json);
            }
            return new Schema(inputSchema);
        }

        private String required(String key) {
            String value = json.getString(key);
            if (value == null) throw new AssertionError("a tool entry has no " + key + ": " + json);
            return value;
        }
    }

    static final class Schema {
        private final JSONObject json;

        private Schema(JSONObject json) {
            this.json = json;
        }

        String type() { return json.getString("type"); }

        /** The schema's own keys, so a promise that a key stays absent is still an assertion. */
        Set<String> keys() { return json.keySet(); }

        Set<String> propertyNames() {
            return properties().keySet();
        }

        Property property(String name) {
            JSONObject property = properties().getJSONObject(name);
            if (property == null) {
                throw new AssertionError("no property named " + name + "; the schema has "
                        + properties().keySet());
            }
            return new Property(property);
        }

        /** The declared required list, or no names when the key is absent. */
        List<String> required() {
            JSONArray required = json.getJSONArray("required");
            return required == null ? List.of() : required.toJavaList(String.class);
        }

        private JSONObject properties() {
            JSONObject properties = json.getJSONObject("properties");
            if (properties == null) {
                throw new AssertionError("the schema has no properties: " + json);
            }
            return properties;
        }
    }

    static final class Property {
        private final JSONObject json;

        private Property(JSONObject json) {
            this.json = json;
        }

        String type() { return json.getString("type"); }
        String description() { return json.getString("description"); }
        String string(String key) { return json.getString(key); }
        boolean flag(String key) { return json.getBooleanValue(key); }
        List<String> enumValues() {
            JSONArray values = json.getJSONArray("enum");
            return values == null ? List.of() : values.toJavaList(String.class);
        }

        Set<String> keys() { return json.keySet(); }

        /** The schema this property nests under one of its own keys -- a body's properties. */
        Property nested(String key) {
            JSONObject nested = json.getJSONObject(key);
            if (nested == null) {
                throw new AssertionError("the property has no " + key + ": " + json);
            }
            return new Property(nested);
        }
    }
}
