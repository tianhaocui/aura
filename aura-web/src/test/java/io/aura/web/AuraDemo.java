package io.aura.web;

import io.aura.Aura;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AuraDemo {

    record User(int id, String name, String email) {}

    static final Map<Integer, User> users = new ConcurrentHashMap<>();
    static final AtomicInteger idGen = new AtomicInteger(0);

    public static void main(String[] args) {
        users.put(idGen.incrementAndGet(), new User(1, "Alice", "alice@example.com"));
        users.put(idGen.incrementAndGet(), new User(2, "Bob", "bob@example.com"));

        Aura app = Aura.create()
            .port(8080)
            .env("dev")
            .workers(200)
            .prop("app.name", "Aura Demo")
            .staticFiles("/public")
            .onStart(a -> System.out.println("App: " + a.prop("app.name")))
            .onStop(a -> System.out.println("Shutting down..."))
            .routes((BaseRouter r) -> {
                // global logging middleware
                r.before(ctx -> ctx.set("startTime", System.currentTimeMillis()));
                r.after(ctx -> {
                    long start = ctx.get("startTime", Long.class);
                    System.out.printf("%s %s → %dms%n",
                            ctx.method(), ctx.url(),
                            System.currentTimeMillis() - start);
                });

                // health check
                r.get("/health", ctx -> ctx.text("ok"));

                // user API group with auth middleware
                r.group("/api/users", api -> {
                    api.before(ctx -> {
                        String token = ctx.header("Authorization");
                        if (token == null || !token.equals("Bearer secret")) {
                            ctx.status(401).text("Unauthorized");
                            throw new UnauthorizedException();
                        }
                    });

                    api.get("", ctx -> ctx.json(users.values().stream().toList()));

                    api.get("/{id}", ctx -> {
                        int id = Integer.parseInt(ctx.path("id"));
                        User user = users.get(id);
                        if (user == null) {
                            ctx.status(404).json(Map.of("error", "User not found"));
                            return;
                        }
                        ctx.json(user);
                    });

                    api.post("", ctx -> {
                        User input = ctx.body(User.class);
                        int id = idGen.incrementAndGet();
                        User created = new User(id, input.name(), input.email());
                        users.put(id, created);
                        ctx.status(201).json(created);
                    });

                    api.delete("/{id}", ctx -> {
                        int id = Integer.parseInt(ctx.path("id"));
                        User removed = users.remove(id);
                        if (removed == null) {
                            ctx.status(404).json(Map.of("error", "User not found"));
                            return;
                        }
                        ctx.json(Map.of("deleted", id));
                    });
                });

                // exception handlers
                r.exception(UnauthorizedException.class, (e, ctx) -> {});
                r.exception(NumberFormatException.class, (e, ctx) ->
                    ctx.status(400).json(Map.of("error", "Invalid number: " + e.getMessage())));
                r.exception(Exception.class, (e, ctx) ->
                    ctx.status(500).json(Map.of("error", "Internal error")));
            });

        app.start();
    }

    static class UnauthorizedException extends RuntimeException {}
}
