package io.aura.db;

import io.aura.Aura;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseModuleTest {
    @Test
    void databaseModuleContributesDbToDependentModule() {
        Db db = Db.create("jdbc:h2:mem:module;DB_CLOSE_DELAY=-1", "sa", "");
        Aura app = Aura.create()
                .use(new AuraModule() {
                    @Override public ModuleMetadata descriptor() {
                        return ModuleMetadata.of("orders", "1.0.0").requires("db");
                    }

                    @Override public void install(ModuleContext context) {
                        assertThat(context.require(Db.class)).isSameAs(db);
                    }
                })
                .use(DatabaseModule.create(db));

        app.prepareModules();
        assertThat(app.getBean(Db.class)).isSameAs(db);
        db.close();
    }
}
