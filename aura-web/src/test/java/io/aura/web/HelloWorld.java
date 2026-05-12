package io.aura.web;

import io.aura.Aura;

public class HelloWorld {
    public static void main(String[] args) {
        Aura.create()
            .port(8080)
            .routes((BaseRouter r) -> {
                r.get("/hello", ctx -> ctx.text("hi"));
            })
            .start();
    }
}
