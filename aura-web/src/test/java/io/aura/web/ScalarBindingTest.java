package io.aura.web;

import io.aura.annotation.Get;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScalarBindingTest {
    @Test
    void reflectionRoutesBindEverySupportedJavaScalarFromQueryParameters() {
        Router router = new Router();
        ServiceRegistrar.register(new ScalarService(), router);
        TestClient client = TestApplications.client(router);

        client.get("/scalars")
                .query("floatValue", "1.5")
                .query("boxedFloat", "2.5")
                .query("shortValue", "3")
                .query("boxedShort", "4")
                .query("byteValue", "5")
                .query("boxedByte", "6")
                .query("charValue", "x")
                .query("boxedChar", "y")
                .expect(200)
                .bodyContains("1.5/2.5/3/4/5/6/x/y");
    }

    static final class ScalarService {
        @Get("/scalars")
        public String values(float floatValue, Float boxedFloat, short shortValue, Short boxedShort,
                             byte byteValue, Byte boxedByte, char charValue, Character boxedChar) {
            return floatValue + "/" + boxedFloat + "/" + shortValue + "/" + boxedShort + "/"
                    + byteValue + "/" + boxedByte + "/" + charValue + "/" + boxedChar;
        }
    }
}
