package io.aura.db.flyway;

import io.aura.db.MigrationContext;
import io.aura.db.SchemaMigrationResult;
import io.aura.db.SchemaMigrator;
import org.flywaydb.core.Flyway;

import java.util.Arrays;
import java.util.Objects;

/** Runs Flyway migrations during {@code DatabaseModule} startup. */
public final class FlywaySchemaMigrator implements SchemaMigrator {
    public static final String ID = "flyway";

    private final String[] locations;

    private FlywaySchemaMigrator(String[] locations) {
        this.locations = locations;
    }

    /** Uses one or more classpath directories containing Flyway migrations. */
    public static FlywaySchemaMigrator classpath(String... locations) {
        Objects.requireNonNull(locations, "locations");
        if (locations.length == 0) {
            throw new IllegalArgumentException("At least one Flyway classpath location is required");
        }
        String[] normalized = Arrays.stream(locations)
                .map(location -> normalizeClasspathLocation(location))
                .toArray(String[]::new);
        return new FlywaySchemaMigrator(normalized);
    }

    @Override public SchemaMigrationResult migrate(MigrationContext context) {
        Objects.requireNonNull(context, "context");
        var result = Flyway.configure()
                .dataSource(context.dataSource())
                .locations(locations)
                .failOnMissingLocations(true)
                .load()
                .migrate();
        return new SchemaMigrationResult(ID, result.migrationsExecuted);
    }

    private static String normalizeClasspathLocation(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Flyway classpath location must not be blank");
        }
        String value = location.trim();
        if (value.startsWith("classpath:")) {
            value = value.substring("classpath:".length());
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("Flyway classpath location must not be blank");
        }
        return "classpath:" + value;
    }
}
