package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TypeUtilTest {

    enum Color { RED, GREEN }

    static class MyPojo {
        public MyPojo() {}
        public String name;
    }

    @Test
    void isPojo_primitive_returnsFalse() {
        assertThat(TypeUtil.isPojo(int.class)).isFalse();
    }

    @Test
    void isPojo_array_returnsFalse() {
        assertThat(TypeUtil.isPojo(String[].class)).isFalse();
    }

    @Test
    void isPojo_enum_returnsFalse() {
        assertThat(TypeUtil.isPojo(Color.class)).isFalse();
    }

    @Test
    void isPojo_javaString_returnsFalse() {
        assertThat(TypeUtil.isPojo(String.class)).isFalse();
    }

    @Test
    void isPojo_javaList_returnsFalse() {
        assertThat(TypeUtil.isPojo(List.class)).isFalse();
    }

    @Test
    void isPojo_customClass_returnsTrue() {
        assertThat(TypeUtil.isPojo(MyPojo.class)).isTrue();
    }
}
