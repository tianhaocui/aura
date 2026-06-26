package io.aura.web;

import io.aura.Aura;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class BeforeBuilder {

    private final Aura app;
    private final List<String> excludes = new ArrayList<>();
    private final BaseHandler original;

    public BeforeBuilder(Aura app, BaseHandler handler) {
        this(app, handler, app.beforeHandlers());
    }

    public BeforeBuilder(Aura app, BaseHandler handler, List<BaseHandler> targetList) {
        this.app = app;
        this.original = handler;
        targetList.add(ctx -> {
            String path = ctx.url();
            for (String ex : excludes) {
                if (path.equals(ex) || (ex.endsWith("*") && path.startsWith(ex.substring(0, ex.length() - 1)))) {
                    return;
                }
            }
            original.handle(ctx);
        });
    }

    public BeforeBuilder exclude(String... paths) {
        excludes.addAll(List.of(paths));
        return this;
    }

    public Aura and() {
        return app;
    }

    public BeforeBuilder before(BaseHandler handler) {
        return app.before(handler);
    }

    public BeforeBuilder after(BaseHandler handler) {
        return app.after(handler);
    }

    public Aura routes(Consumer<BaseRouter> config) {
        return app.routes(config);
    }
}
