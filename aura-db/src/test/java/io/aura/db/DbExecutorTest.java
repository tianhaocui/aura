package io.aura.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class DbExecutorTest {
    @Test
    void independentTransactionExecutorIsBounded() throws Exception {
        Field field = Db.class.getDeclaredField("INDEPENDENT_TX_POOL");
        field.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) field.get(null);

        assertThat(executor.getMaximumPoolSize()).isEqualTo(executor.getCorePoolSize());
        assertThat(executor.getQueue().remainingCapacity()).isEqualTo(256);
    }
}
