package io.aura.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScalarTypeTest {

    @Test
    void resolvesBinaryNamesAsJsonScalars() {
        assertThat(ScalarType.jsonTypeName("int")).isEqualTo("integer");
        assertThat(ScalarType.jsonTypeName("java.lang.Integer")).isEqualTo("integer");
        assertThat(ScalarType.jsonTypeName("java.lang.Long")).isEqualTo("integer");
        assertThat(ScalarType.jsonTypeName("byte")).isEqualTo("integer");
        assertThat(ScalarType.jsonTypeName("java.lang.Boolean")).isEqualTo("boolean");
        assertThat(ScalarType.jsonTypeName("java.lang.String")).isEqualTo("string");
        assertThat(ScalarType.jsonTypeName("java.lang.Character")).isEqualTo("string");
        assertThat(ScalarType.jsonTypeName("java.math.BigDecimal")).isEqualTo("number");
        assertThat(ScalarType.jsonTypeName("java.math.BigInteger")).isEqualTo("number");
    }

    @Test
    void resolvesTheSimpleNamesReflectionSummariesReport() {
        assertThat(ScalarType.jsonTypeName("Long")).isEqualTo("integer");
        assertThat(ScalarType.jsonTypeName("BigDecimal")).isEqualTo("number");
        assertThat(ScalarType.jsonTypeName("String")).isEqualTo("string");
    }

    @Test
    void structuredTypesAreNotScalars() {
        assertThat(ScalarType.isScalar("io.aura.demo.CreateOrderRequest")).isFalse();
        assertThat(ScalarType.isScalar("java.time.LocalDate")).isFalse();
        assertThat(ScalarType.isScalar("java.util.List")).isFalse();
        assertThat(ScalarType.isScalar("[Ljava.lang.String;")).isFalse();
        assertThat(ScalarType.isScalar(null)).isFalse();
    }

    @Test
    void bindsTheTypesItsOwnTableRenders() {
        assertThat(ScalarType.fromString("7", int.class)).isEqualTo(7);
        assertThat(ScalarType.fromString("7", Integer.class)).isEqualTo(7);
        assertThat(ScalarType.fromString("7", short.class)).isEqualTo((short) 7);
        assertThat(ScalarType.fromString("7", byte.class)).isEqualTo((byte) 7);
        assertThat(ScalarType.fromString("7", long.class)).isEqualTo(7L);
        assertThat(ScalarType.fromString("7", Long.class)).isEqualTo(7L);
        assertThat(ScalarType.fromString("7", Short.class)).isEqualTo((short) 7);
        assertThat(ScalarType.fromString("7", Byte.class)).isEqualTo((byte) 7);
        assertThat(ScalarType.fromString("7.5", float.class)).isEqualTo(7.5f);
        assertThat(ScalarType.fromString("7.5", Float.class)).isEqualTo(7.5f);
        assertThat(ScalarType.fromString("7.5", double.class)).isEqualTo(7.5d);
        assertThat(ScalarType.fromString("7.5", Double.class)).isEqualTo(7.5d);
        assertThat(ScalarType.fromString("7", java.math.BigInteger.class))
                .isEqualTo(new java.math.BigInteger("7"));
        assertThat(ScalarType.fromString("true", Boolean.class)).isEqualTo(Boolean.TRUE);
        assertThat(ScalarType.fromString("x", Character.class)).isEqualTo('x');
        assertThat(ScalarType.fromString("7.5", java.math.BigDecimal.class))
                .isEqualTo(new java.math.BigDecimal("7.5"));
        assertThat(ScalarType.fromString("true", boolean.class)).isEqualTo(true);
        assertThat(ScalarType.fromString("TRUE", boolean.class)).isEqualTo(true);
        assertThat(ScalarType.fromString("abc", String.class)).isEqualTo("abc");
        assertThat(ScalarType.fromString("x", char.class)).isEqualTo('x');
    }

    @Test
    void absentValuesBindToTheTypeOwnAbsence() {
        assertThat(ScalarType.fromString(null, int.class)).isEqualTo(0);
        assertThat(ScalarType.fromString(null, boolean.class)).isEqualTo(false);
        assertThat(ScalarType.fromString(null, char.class)).isEqualTo('\0');
        assertThat(ScalarType.fromString(null, Integer.class)).isNull();
        assertThat(ScalarType.fromString(null, Boolean.class)).isNull();
        assertThat(ScalarType.fromString(null, java.math.BigInteger.class)).isNull();
    }

    @Test
    void malformedValuesFailTheSameWayOnEveryTransport() {
        assertThatThrownBy(() -> ScalarType.fromString("seven", int.class))
                .isInstanceOf(NumberFormatException.class)
                .hasMessage("Invalid integer: seven");
        assertThatThrownBy(() -> ScalarType.fromString("seven", long.class))
                .isInstanceOf(NumberFormatException.class)
                .hasMessage("Invalid long: seven");
        assertThatThrownBy(() -> ScalarType.fromString("seven", java.math.BigDecimal.class))
                .isInstanceOf(NumberFormatException.class)
                .hasMessage("Invalid number: seven");
    }

    @Test
    void enumsBindByNameAndEmptyMeansAbsent() {
        assertThat(ScalarType.fromString("READ", Level.class)).isEqualTo(Level.READ);
        assertThat(ScalarType.fromString("", Level.class)).isNull();
        assertThat(ScalarType.fromString(null, Level.class)).isNull();
    }

    @Test
    void bodyRuleIsTheMirrorOfTheScalarTable() {
        assertThat(ScalarType.isBody(int.class)).isFalse();
        assertThat(ScalarType.isBody(String.class)).isFalse();
        assertThat(ScalarType.isBody(Level.class)).isFalse();
        assertThat(ScalarType.isBody(java.util.List.class)).isTrue();
        assertThat(ScalarType.isBody(java.time.LocalDate.class)).isTrue();
    }

    enum Level { READ, WRITE }
}
