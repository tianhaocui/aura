package io.aura;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class AuraConfigTest {

    // --- set() does not auto-apply framework fields ---

    @Test
    void set_frameworkKey_doesNotUpdatePortField() {
        // set() only writes to the props map; applyFrameworkProps() is not called again.
        // port() reflects the builder value, not the props map entry.
        Aura app = Aura.create().set("aura.port", "9000");
        assertThat(app.prop("aura.port")).isEqualTo("9000");
        assertThat(app.port()).isEqualTo(8080); // field unchanged
    }

    @Test
    void set_frameworkKey_doesNotUpdateEnvField() {
        Aura app = Aura.create().set("aura.env", "staging");
        assertThat(app.prop("aura.env")).isEqualTo("staging");
        assertThat(app.env()).isEqualTo("dev"); // field unchanged
    }

    @Test
    void set_frameworkKey_doesNotUpdateWorkersField() {
        Aura app = Aura.create().set("aura.workers", "50");
        assertThat(app.prop("aura.workers")).isEqualTo("50");
        assertThat(app.workers()).isEqualTo(200); // field unchanged
    }

    // --- prop(key, int) edge cases ---

    @Test
    void propWithDefault_throwsForNonNumericValue() {
        Aura app = Aura.create().set("bad.int", "notanumber");
        assertThatThrownBy(() -> app.prop("bad.int", 5))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void propWithDefault_parsesZero() {
        Aura app = Aura.create().set("zero.val", "0");
        assertThat(app.prop("zero.val", 99)).isEqualTo(0);
    }

    @Test
    void propWithDefault_parsesNegative() {
        Aura app = Aura.create().set("neg.val", "-1");
        assertThat(app.prop("neg.val", 10)).isEqualTo(-1);
    }

    // --- set() fluent chaining ---

    @Test
    void set_returnsSameInstance() {
        Aura app = Aura.create();
        Aura result = app.set("k", "v");
        assertThat(result).isSameAs(app);
    }

    @Test
    void set_multipleKeys_allReadable() {
        Aura app = Aura.create()
                .set("a", "1")
                .set("b", "2")
                .set("c", "3");
        assertThat(app.prop("a")).isEqualTo("1");
        assertThat(app.prop("b")).isEqualTo("2");
        assertThat(app.prop("c")).isEqualTo("3");
    }

    // --- scan() accumulation ---

    @Test
    void scan_singlePackage() {
        Aura app = Aura.create().scan("com.example");
        assertThat(app.scanPackages()).containsExactly("com.example");
    }

    @Test
    void scan_multipleCallsAccumulate() {
        Aura app = Aura.create()
                .scan("com.example.a")
                .scan("com.example.b");
        assertThat(app.scanPackages()).containsExactly("com.example.a", "com.example.b");
    }

    @Test
    void scan_varargs_addsAll() {
        Aura app = Aura.create().scan("pkg.a", "pkg.b", "pkg.c");
        assertThat(app.scanPackages()).containsExactly("pkg.a", "pkg.b", "pkg.c");
    }

    // --- spa() and staticFiles() ---

    @Test
    void spa_defaultIsFalse() {
        Aura app = Aura.create();
        assertThat(app.spa()).isFalse();
    }

    @Test
    void spa_trueEnablesSpaMode() {
        Aura app = Aura.create().spa(true);
        assertThat(app.spa()).isTrue();
    }

    @Test
    void spa_falseDisablesSpaMode() {
        Aura app = Aura.create().spa(true).spa(false);
        assertThat(app.spa()).isFalse();
    }

    @Test
    void staticFiles_defaultIsNull() {
        Aura app = Aura.create();
        assertThat(app.staticFilesPath()).isNull();
    }

    @Test
    void staticFiles_setsPath() {
        Aura app = Aura.create().staticFiles("/public");
        assertThat(app.staticFilesPath()).isEqualTo("/public");
    }

    @Test
    void staticFiles_overwritesPreviousValue() {
        Aura app = Aura.create().staticFiles("/old").staticFiles("/new");
        assertThat(app.staticFilesPath()).isEqualTo("/new");
    }

    // --- onStart hooks ---

    @Test
    void onStart_hookRegistered_notFiredBeforeStart() {
        AtomicInteger counter = new AtomicInteger(0);
        Aura app = Aura.create().onStart(a -> counter.incrementAndGet());
        // hooks fire only inside start(), which requires AuraStarter
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test
    void onStart_multipleHooks_allRegistered() {
        List<Integer> order = new ArrayList<>();
        Aura app = Aura.create()
                .onStart(a -> order.add(1))
                .onStart(a -> order.add(2))
                .onStart(a -> order.add(3));
        // hooks not fired yet — just verify registration doesn't throw
        assertThat(order).isEmpty();
    }

    // --- onStop hooks — fired in reverse order via stop() ---

    @Test
    void onStop_firesInReverseOrder() {
        List<Integer> order = new ArrayList<>();
        Aura app = Aura.create()
                .onStop(a -> order.add(1))
                .onStop(a -> order.add(2))
                .onStop(a -> order.add(3));
        // stop() is safe to call without a starter (null-checked internally)
        app.stop();
        assertThat(order).containsExactly(3, 2, 1);
    }

    @Test
    void onStop_singleHook_fires() {
        AtomicInteger counter = new AtomicInteger(0);
        Aura app = Aura.create().onStop(a -> counter.incrementAndGet());
        app.stop();
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void onStop_idempotent_secondStopIsNoop() {
        AtomicInteger counter = new AtomicInteger(0);
        Aura app = Aura.create().onStop(a -> counter.incrementAndGet());
        app.stop();
        app.stop(); // second call must not fire hooks again
        assertThat(counter.get()).isEqualTo(1);
    }

    // --- register / getBean type hierarchy ---

    @Test
    void getBean_byInterface_whenConcreteRegistered() {
        Aura app = Aura.create();
        // Runnable is an interface; register a lambda (concrete anonymous class)
        Runnable r = () -> {};
        app.register(r);
        assertThat(app.getBean(Runnable.class)).isSameAs(r);
    }

    @Test
    void register_overwritesPreviousForSameType() {
        Aura app = Aura.create();
        StringBuilder first = new StringBuilder("first");
        StringBuilder second = new StringBuilder("second");
        app.register(first);
        app.register(second);
        // registry is keyed by exact class; second registration wins
        assertThat(app.getBean(StringBuilder.class)).isSameAs(second);
    }

    // --- named registry type check ---

    @Test
    void getNamed_byAssignableType_returnsBean() {
        Aura app = Aura.create();
        ArrayList<String> list = new ArrayList<>();
        app.register("myList", list);
        // ArrayList is assignable to List
        assertThat(app.getBean("myList", List.class)).isSameAs(list);
    }

    @Test
    void getNamed_exactTypeMatch_returnsBean() {
        Aura app = Aura.create();
        String value = "hello";
        app.register("greeting", value);
        assertThat(app.getBean("greeting", String.class)).isEqualTo("hello");
    }
}
