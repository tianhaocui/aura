package io.aura.web.testfixtures;

import io.aura.annotation.Get;
import io.aura.annotation.Path;
import io.aura.web.BaseContext;

@Path("/injected")
public class InjectedController {

    private final FakeDb db;

    public InjectedController(FakeDb db) {
        this.db = db;
    }

    @Get("")
    public void get(BaseContext ctx) {
        ctx.text(db.query());
    }
}
