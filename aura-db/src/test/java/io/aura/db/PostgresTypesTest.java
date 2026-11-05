package io.aura.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PostgresTypesTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg16");

    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.create("pg-types", POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void jsonbRoundTripsThroughInsertSelectAndFilter() {
        db.execute("CREATE TABLE jsonb_docs (id BIGSERIAL PRIMARY KEY, payload JSONB NOT NULL)");
        db.execute("INSERT INTO jsonb_docs (payload) VALUES (?)",
                Jsonb.of(Map.of("kind", "note", "stars", 5)));

        Row row = db.findOne("SELECT payload FROM jsonb_docs");

        assertThat(com.alibaba.fastjson2.JSON.parseObject(row.getJson("payload")))
                .containsEntry("kind", "note");
        assertThat(row.getJson("payload", Map.class))
                .containsEntry("kind", "note")
                .containsEntry("stars", 5);
        assertThat(db.find("SELECT * FROM jsonb_docs WHERE payload @> ?",
                Jsonb.raw("{\"kind\":\"note\"}"))).hasSize(1);
        assertThat(db.find("SELECT * FROM jsonb_docs WHERE payload @> ?",
                Jsonb.of(Map.of("stars", 5)))).hasSize(1);
    }

    @Test
    void rowInsertAndBatchBindJsonbParameters() {
        db.execute("CREATE TABLE jsonb_rows (id BIGSERIAL PRIMARY KEY, payload JSONB NOT NULL)");

        Row.of("jsonb_rows").setJson("payload", Map.of("kind", "row")).insert(db);
        db.batch("INSERT INTO jsonb_rows (payload) VALUES (?)", List.of(
                new Object[]{Jsonb.raw("{\"kind\":\"batch-1\"}")},
                new Object[]{Jsonb.raw("{\"kind\":\"batch-2\"}")}));

        assertThat(db.find("SELECT * FROM jsonb_rows")).hasSize(3);
        assertThat(db.find("SELECT * FROM jsonb_rows WHERE payload @> ?",
                Jsonb.raw("{\"kind\":\"batch-2\"}"))).hasSize(1);
        assertThat(db.findOne("SELECT * FROM jsonb_rows WHERE payload @> ?",
                Jsonb.raw("{\"kind\":\"row\"}")).getJson("payload", Map.class))
                .containsEntry("kind", "row");
    }

    @Test
    void vectorRoundTripsAndParticipatesInDistanceSearch() {
        db.execute("CREATE EXTENSION IF NOT EXISTS vector");
        db.execute("CREATE TABLE vectors (id BIGSERIAL PRIMARY KEY, embedding vector(3) NOT NULL)");
        db.execute("INSERT INTO vectors (embedding) VALUES (?)", PgVector.of(1f, 0f, 0f));
        db.execute("INSERT INTO vectors (embedding) VALUES (?)", PgVector.of(0f, 1f, 0f));

        Row stored = db.findOne("SELECT embedding FROM vectors WHERE embedding = ?",
                PgVector.of(0f, 1f, 0f));
        assertThat(stored.getVector("embedding")).containsExactly(0f, 1f, 0f);

        List<Row> nearest = db.find(
                "SELECT id, embedding FROM vectors ORDER BY embedding <-> ? LIMIT 1",
                PgVector.of(0.9f, 0.1f, 0f));
        assertThat(nearest).hasSize(1);
        assertThat(nearest.get(0).getVector("embedding")).containsExactly(1f, 0f, 0f);
    }
}
