package io.aura.db;

import org.junit.jupiter.api.Test;

import io.aura.contract.CancellationToken;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void aBlockThatReactsToTheInterruptRollsBackAndSaysSo() throws Exception {
        Db db = Db.create("jdbc:h2:mem:independent-rollback;DB_CLOSE_DELAY=-1", "sa", "");
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            // The block runs on the pool thread, so the caller waits for it to be running before
            // the deadline can race the task's start: otherwise a busy pool cancels a task that
            // never began and the outcome becomes "unknown" for reasons the test did not mean.
            Thread caller = call(failure, () ->
                    db.transactionIndependent(Duration.ofMillis(500), new CancellationToken(),
                            cancellation -> {
                                started.countDown();
                                try {
                                    Thread.sleep(10_000);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    cancellation.throwIfCancelled();
                                }
                                return null;
                            }));
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("the block is running, so the timeout can interrupt it").isTrue();
            caller.join(10_000);

            assertThat(failure.get())
                    .isInstanceOf(Db.DbException.class)
                    .hasMessageContaining("rolled back");
        } finally {
            db.close();
        }
    }

    @Test
    void aBlockThatIgnoresTheInterruptCommitsAfterTheDeadlineAndSaysSo() throws Exception {
        Db db = Db.create("jdbc:h2:mem:independent-commit;DB_CLOSE_DELAY=-1", "sa", "");
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            // This block never looks at the interrupt; it stops only once the deadline has
            // cancelled the token, so its commit necessarily lands after the caller gave up.
            Thread caller = call(failure, () ->
                    db.transactionIndependent(Duration.ofMillis(500), new CancellationToken(),
                            cancellation -> {
                                started.countDown();
                                while (!cancellation.isCancelled()) {
                                    try {
                                        Thread.sleep(5);
                                    } catch (InterruptedException ignored) {
                                        // Deliberately ignored: the block finishes and commits.
                                    }
                                }
                                return "late";
                            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            caller.join(10_000);

            assertThat(failure.get())
                    .isInstanceOf(Db.DbException.class)
                    .hasMessageContaining("committed after the deadline");
        } finally {
            db.close();
        }
    }

    @Test
    void aBlockThatOutlivesTheGraceWindowLeavesTheOutcomeUnknown() throws Exception {
        Db db = Db.create("jdbc:h2:mem:independent-unknown;DB_CLOSE_DELAY=-1", "sa", "");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Thread caller = call(failure, () ->
                    db.transactionIndependent(Duration.ofMillis(200), new CancellationToken(),
                            cancellation -> {
                                started.countDown();
                                awaitIgnoringInterrupts(release);
                                return "late";
                            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            caller.join(10_000);

            assertThat(failure.get())
                    .isInstanceOf(Db.DbException.class)
                    .hasMessageContaining("outcome is unknown");
            release.countDown();
            awaitIndependentPoolIdle();
        } finally {
            release.countDown();
            db.close();
        }
    }

    private static Thread call(AtomicReference<Throwable> failure, Runnable body) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.start();
        return thread;
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // The point of this block is that it does not stop when interrupted.
            }
        }
    }

    /**
     * The outlives-the-grace test releases its block only after the caller has already reported
     * "unknown". Closing the Db while that worker is still committing would race the pool, so the
     * test waits for the pool to go idle first.
     */
    private static void awaitIndependentPoolIdle() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Db.independentTransactionMetrics().active() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void shuttingTheSharedPoolDownDoesNotSilenceLaterIndependentTransactions() {
        Db db = Db.create("jdbc:h2:mem:independent-shutdown;DB_CLOSE_DELAY=-1", "sa", "");
        long rejectedBefore = Db.independentTransactionMetrics().rejected();
        try {
            assertThat(db.transactionIndependent(() -> "first")).isEqualTo("first");
            Db.shutdownPool();

            // The shutdown belongs to the threads that existed when it was asked for. Making it
            // permanent would leave every Db in the process unable to run an independent
            // transaction, including the ones that were never closed.
            assertThat(db.transactionIndependent(() -> "second")).isEqualTo("second");
            assertThat(Db.independentTransactionMetrics().rejected()).isEqualTo(rejectedBefore);
        } finally {
            db.close();
        }
    }
}
