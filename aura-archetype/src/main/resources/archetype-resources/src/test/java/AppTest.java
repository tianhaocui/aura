package ${package};

import io.aura.AuraApp;
import io.aura.web.TestClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppTest {
    @Test
    void verifiesTheSameRoutesWithoutOpeningAServer() {
        try (AuraApp app = App.create().prepare()) {
            TestClient.Response response = TestClient.of(app)
                    .get("/hello")
                    .expect(200);

            assertEquals("hello", response.body());
        }
    }
}
