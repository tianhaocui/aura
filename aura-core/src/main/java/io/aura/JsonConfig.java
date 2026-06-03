package io.aura;

import java.util.ArrayList;
import java.util.List;

public class JsonConfig {

    private String dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS";
    private boolean writeNulls;
    private final List<String> features = new ArrayList<>();

    public JsonConfig dateFormat(String format) {
        this.dateFormat = format;
        return this;
    }

    public JsonConfig writeNulls(boolean enabled) {
        this.writeNulls = enabled;
        return this;
    }

    public JsonConfig feature(String feature) {
        this.features.add(feature);
        return this;
    }

    public String dateFormat() { return dateFormat; }
    public boolean writeNulls() { return writeNulls; }
    public List<String> features() { return features; }
}
