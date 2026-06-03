package io.aura;

public class JsonConfig {

    private String dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS";
    private boolean writeNulls;

    public JsonConfig dateFormat(String format) {
        this.dateFormat = format;
        return this;
    }

    public JsonConfig writeNulls(boolean enabled) {
        this.writeNulls = enabled;
        return this;
    }

    public String dateFormat() { return dateFormat; }
    public boolean writeNulls() { return writeNulls; }
}
