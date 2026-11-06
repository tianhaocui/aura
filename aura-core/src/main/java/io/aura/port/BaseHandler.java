package io.aura.port;

@FunctionalInterface
public interface BaseHandler {
    void handle(BaseContext ctx) throws Exception;
}
