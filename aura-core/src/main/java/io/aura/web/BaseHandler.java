package io.aura.web;

@FunctionalInterface
public interface BaseHandler {
    void handle(BaseContext ctx) throws Exception;
}
