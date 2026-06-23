package io.aura;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.*;

class SelfCheckTest {

    @Test
    void detectsSyntheticParameterNames() throws Exception {
        Method m = SampleService.class.getDeclaredMethod("get", String.class);
        Parameter p = m.getParameters()[0];
        assertThat(p.getName()).isEqualTo("id");
    }

    @Test
    void selfCheck_detectsRealParameterNames() throws Exception {
        Method m = SampleService.class.getDeclaredMethod("create", String.class, int.class);
        assertThat(m.getParameters()[0].getName()).isEqualTo("name");
        assertThat(m.getParameters()[1].getName()).isEqualTo("age");
    }

    public static class SampleService {
        public void get(String id) {}
        public void create(String name, int age) {}
    }
}
