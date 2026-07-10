package io.aura;

import io.aura.annotation.Scheduled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class SchedulerTest {

    public static class ValidService {
        public volatile int count = 0;
        private final CountDownLatch latch = new CountDownLatch(2);

        @Scheduled(fixedRate = 50)
        public void tick() {
            count++;
            latch.countDown();
        }

        public CountDownLatch latch() { return latch; }
    }

    public static class CronService {
        @Scheduled(cron = "0 0 * * *")
        public void daily() {}
    }

    public static class InvalidPrivateMethod {
        @Scheduled(fixedRate = 100)
        private void hidden() {}
    }

    public static class InvalidWithParams {
        @Scheduled(fixedRate = 100)
        public void tick(int x) {}
    }

    public static class InvalidNoSchedule {
        @Scheduled
        public void tick() {}
    }

    public static class InvalidMultiple {
        @Scheduled(fixedRate = 100, fixedDelay = 200)
        public void tick() {}
    }

    @Test
    void fixedRate_fires() throws InterruptedException {
        ValidService svc = new ValidService();
        Scheduler scheduler = new Scheduler();
        scheduler.scan(List.of(svc));

        assertThat(scheduler.tasks()).hasSize(1);
        assertThat(scheduler.tasks().get(0).method()).isEqualTo("ValidService.tick");
        assertThat(scheduler.tasks().get(0).schedule()).contains("fixedRate");

        svc.latch().await(2, TimeUnit.SECONDS);
        scheduler.shutdown();
        assertThat(svc.count).isGreaterThanOrEqualTo(2);
    }

    @Test
    void cronService_registered() {
        CronService svc = new CronService();
        Scheduler scheduler = new Scheduler();
        scheduler.scan(List.of(svc));

        assertThat(scheduler.tasks()).hasSize(1);
        assertThat(scheduler.tasks().get(0).schedule()).contains("cron: 0 0 * * *");
        scheduler.shutdown();
    }

    @Test
    void privateMethod_throws() {
        InvalidPrivateMethod svc = new InvalidPrivateMethod();
        Scheduler scheduler = new Scheduler();
        assertThatThrownBy(() -> scheduler.scan(List.of(svc)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be public");
        scheduler.shutdown();
    }

    @Test
    void methodWithParams_throws() {
        InvalidWithParams svc = new InvalidWithParams();
        Scheduler scheduler = new Scheduler();
        assertThatThrownBy(() -> scheduler.scan(List.of(svc)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no parameters");
        scheduler.shutdown();
    }

    @Test
    void noScheduleAttribute_throws() {
        InvalidNoSchedule svc = new InvalidNoSchedule();
        Scheduler scheduler = new Scheduler();
        assertThatThrownBy(() -> scheduler.scan(List.of(svc)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cron, fixedRate, or fixedDelay");
        scheduler.shutdown();
    }

    @Test
    void multipleScheduleAttributes_throws() {
        InvalidMultiple svc = new InvalidMultiple();
        Scheduler scheduler = new Scheduler();
        assertThatThrownBy(() -> scheduler.scan(List.of(svc)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only one");
        scheduler.shutdown();
    }
}
