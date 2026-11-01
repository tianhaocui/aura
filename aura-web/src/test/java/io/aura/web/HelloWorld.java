package io.aura.web;

import io.aura.AuraConfig;
import io.aura.contract.Route;

public class HelloWorld {
    public static void main(String[] args) {
        TestApplications.app(
                new AuraConfig().port(8080),
                "hello-world",
                Route.of("GET", "/hello", (BaseHandler) ctx -> ctx.text("hi")))
                .start();
    }
}
