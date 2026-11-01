package io.aura.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
