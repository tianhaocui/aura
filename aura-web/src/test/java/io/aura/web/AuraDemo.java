package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;

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

        AuraConfig config = new AuraConfig()
                .port(8080)
                .env("dev")
                .workers(200)
                .staticFiles("/public");
        AuraApp app = AuraApp.create(config).use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("demo", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.route(route("GET", "/health", ctx -> ctx.text("ok")));
                context.route(route("GET", "/api/users", ctx -> {
                    if (unauthorized(ctx)) return;
                    ctx.json(users.values().stream().toList());
                }));
                context.route(route("GET", "/api/users/{id}", ctx -> {
                    if (unauthorized(ctx)) return;
                    int id = Integer.parseInt(ctx.path("id"));
                    User user = users.get(id);
                    if (user == null) {
                        ctx.status(404).json(Map.of("error", "User not found"));
                        return;
                    }
                    ctx.json(user);
                }));
                context.route(route("POST", "/api/users", ctx -> {
                    if (unauthorized(ctx)) return;
                    User input = ctx.body(User.class);
                    int id = idGen.incrementAndGet();
                    User created = new User(id, input.name(), input.email());
                    users.put(id, created);
                    ctx.status(201).json(created);
                }));
                context.route(route("DELETE", "/api/users/{id}", ctx -> {
                    if (unauthorized(ctx)) return;
                    int id = Integer.parseInt(ctx.path("id"));
                    User removed = users.remove(id);
                    if (removed == null) {
                        ctx.status(404).json(Map.of("error", "User not found"));
                        return;
                    }
                    ctx.json(Map.of("deleted", id));
                }));
            }
        });

        System.out.println("App: Aura Demo");
        app.start();
    }

    private static RouteContribution route(String method, String path, BaseHandler handler) {
        return new RouteContribution(method, path, (BaseHandler) ctx -> {
            long start = System.currentTimeMillis();
            try {
                handler.handle(ctx);
            } catch (NumberFormatException error) {
                ctx.status(400).json(Map.of("error", "Invalid number: " + error.getMessage()));
            } catch (Exception error) {
                ctx.status(500).json(Map.of("error", "Internal error"));
            } finally {
                System.out.printf("%s %s -> %dms%n", ctx.method(), ctx.url(),
                        System.currentTimeMillis() - start);
            }
        });
    }

    private static boolean unauthorized(BaseContext ctx) {
        if ("Bearer secret".equals(ctx.header("Authorization"))) return false;
        ctx.status(401).text("Unauthorized");
        return true;
    }
}
