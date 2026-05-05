package io.aura.web;

@FunctionalInterface
public interface Handler {
    void handle(Context ctx) throws Exception;
}
