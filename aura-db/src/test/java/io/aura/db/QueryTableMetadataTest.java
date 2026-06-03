package io.aura.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.*;

class QueryTableMetadataTest {

    @Test
    void find_preservesTableMetadata() throws Exception {
        var ctor = Query.class.getDeclaredConstructor(Db.class, String.class);
        ctor.setAccessible(true);
        Query q = ctor.newInstance(null, "user");

        Field tableField = Query.class.getDeclaredField("table");
        tableField.setAccessible(true);
        String table = (String) tableField.get(q);
        assertThat(table).isEqualTo("user");
    }
}
