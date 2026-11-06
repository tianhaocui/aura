package io.aura.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * A parameter that knows how to bind itself. Package-private on purpose: the two
 * implementations are the PostgreSQL types the framework ships, not an extension point.
 */
interface SqlBindable {
    void bind(PreparedStatement statement, int index) throws SQLException;
}
