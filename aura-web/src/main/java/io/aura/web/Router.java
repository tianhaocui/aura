package io.aura.web;

import io.aura.port.BaseRouter;

public class Router extends BaseRouter {

    @Override
    public BaseRouter group(String prefix, java.util.function.Consumer<BaseRouter> block) {
        Router sub = new Router();
        block.accept(sub);
        groups.add(new Group(prefix, sub));
        return this;
    }
}
