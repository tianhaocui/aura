package io.aura.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScalarBindingTest {
    @Test
    void lambdaRoutesBindEverySupportedJavaScalarFromQueryParameters() {
        Router router = new Router();
        router.get("/scalars", new LambdaHandler(new ScalarService()));
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

    @Test
    void lambdaRoutesBindEnumsAndBigDecimalFromQueryParameters() {
        Router router = new Router();
        router.get("/money", new LambdaHandler(new MoneyService()));

        TestApplications.client(router).get("/money")
                .query("direction", "UP")
                .query("amount", "12.50")
                .expect(200)
                .bodyContains("UP/12.50");
    }

    @FunctionalInterface
    interface Scalars {
        String values(float floatValue, Float boxedFloat, short shortValue, Short boxedShort,
                      byte byteValue, Byte boxedByte, char charValue, Character boxedChar);
    }

    static final class ScalarService implements Scalars {
        public String values(float floatValue, Float boxedFloat, short shortValue, Short boxedShort,
                             byte byteValue, Byte boxedByte, char charValue, Character boxedChar) {
            return floatValue + "/" + boxedFloat + "/" + shortValue + "/" + boxedShort + "/"
                    + byteValue + "/" + boxedByte + "/" + charValue + "/" + boxedChar;
        }
    }

    enum Direction { UP, DOWN }

    @FunctionalInterface
    interface Money {
        String values(Direction direction, java.math.BigDecimal amount);
    }

    static final class MoneyService implements Money {
        public String values(Direction direction, java.math.BigDecimal amount) {
            return direction + "/" + amount;
        }
    }
}
