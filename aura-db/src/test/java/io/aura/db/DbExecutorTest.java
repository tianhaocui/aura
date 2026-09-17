package io.aura.db;

import org.junit.jupiter.api.Test;

import io.aura.contract.CancellationToken;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DbExecutorTest {
    @Test
    void independentTransactionExecutorIsBoundedAndObservable() {
        var metrics = Db.independentTransactionMetrics();
        assertThat(metrics.observable()).isTrue();
        assertThat(metrics.workerLimit()).isBetween(2, 8);
        assertThat(metrics.queueCapacity()).isEqualTo(256);
    }

    @Test
    void independentTransactionTimeoutCancelsWorkAndIncrementsMetric() {
        Db db = Db.create("jdbc:h2:mem:independent-timeout;DB_CLOSE_DELAY=-1", "sa", "");
        CancellationToken token = new CancellationToken();
        long before = Db.independentTransactionMetrics().timedOut();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            db.transactionIndependent(Duration.ofMillis(40), token, cancellation -> {
                                try {
                                    Thread.sleep(5_000);
                                    return null;
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    cancellation.throwIfCancelled();
                                    return null;
                                }
                            }))
                    .isInstanceOf(Db.DbException.class)
                    .hasMessageContaining("timed out");
            assertThat(token.isCancelled()).isTrue();
            assertThat(Db.independentTransactionMetrics().timedOut()).isEqualTo(before + 1);
        } finally {
            db.close();
        }
    }
}
