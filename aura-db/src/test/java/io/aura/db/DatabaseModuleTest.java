package io.aura.db;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseModuleTest {
    @Test
    void databaseModuleContributesDbToDependentModule() {
        Db db = Db.create("jdbc:h2:mem:module;DB_CLOSE_DELAY=-1", "sa", "");
        AuraApp app = AuraApp.create()
                .use(new AuraModule() {
                    @Override public ModuleMetadata descriptor() {
                        return ModuleMetadata.of("orders", "1.0.0").requires("db");
                    }

                    @Override public void install(ModuleContext context) {
                        assertThat(context.require(Db.class)).isSameAs(db);
                    }
                })
                .use(DatabaseModule.create(db));

        app.prepare();
        assertThat(app.require(Db.class)).isSameAs(db);
        db.close();
    }

    @Test
    void databaseModuleAcceptsMigrationSourceSpi() {
        Db db = Db.create("jdbc:h2:mem:module-migration;DB_CLOSE_DELAY=-1", "sa", "");
        MigrationSource source = () -> java.util.List.of(
                new Migration("1", "create_items", "CREATE TABLE items (id INT PRIMARY KEY)"));
        DatabaseModule module = DatabaseModule.create(db).migrations(source);
        assertThat(module).isNotNull();
        db.migrate(source);
        assertThat(db.find("SELECT * FROM items")).isEmpty();
        db.close();
    }
}
