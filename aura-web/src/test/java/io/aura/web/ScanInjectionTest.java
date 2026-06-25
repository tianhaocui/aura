package io.aura.web;

import io.aura.Aura;
import io.aura.web.testfixtures.FakeDb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScanInjectionTest {

    @Test
    void scanClassGetsConstructorInjection() {
        Aura app = Aura.create();
        FakeDb db = new FakeDb();
        app.register(db);
        app.scan("io.aura.web.testfixtures");

        TestClient client = TestClient.of(app);
        TestClient.Response res = client.get("/injected").execute();
        assertEquals(200, res.status());
        assertEquals("data", res.body());
    }
}
