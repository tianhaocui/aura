package io.aura;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class CronExpressionTest {

    @Test
    void everyMinute() {
        CronExpression cron = new CronExpression("* * * * *");
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 0, 0))).isTrue();
        assertThat(cron.matches(LocalDateTime.of(2026, 6, 15, 14, 30))).isTrue();
    }

    @Test
    void specificMinuteAndHour() {
        CronExpression cron = new CronExpression("30 9 * * *");
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 9, 30))).isTrue();
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 9, 31))).isFalse();
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 10, 30))).isFalse();
    }

    @Test
    void midnight() {
        CronExpression cron = new CronExpression("0 0 * * *");
        assertThat(cron.matches(LocalDateTime.of(2026, 3, 15, 0, 0))).isTrue();
        assertThat(cron.matches(LocalDateTime.of(2026, 3, 15, 0, 1))).isFalse();
    }

    @Test
    void step() {
        CronExpression cron = new CronExpression("*/5 * * * *");
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 12, 0))).isTrue();
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 12, 5))).isTrue();
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 12, 3))).isFalse();
    }

    @Test
    void range() {
        CronExpression cron = new CronExpression("0 9-17 * * *");
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 9, 0))).isTrue();
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 17, 0))).isTrue();
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 8, 0))).isFalse();
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 18, 0))).isFalse();
    }

    @Test
    void list() {
        CronExpression cron = new CronExpression("0 8,12,18 * * *");
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 8, 0))).isTrue();
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 12, 0))).isTrue();
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 18, 0))).isTrue();
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 1, 10, 0))).isFalse();
    }

    @Test
    void dayOfWeek() {
        // Monday = 1, Sunday = 0
        CronExpression cron = new CronExpression("0 9 * * 1-5");
        // 2026-01-05 is Monday
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 5, 9, 0))).isTrue();
        // 2026-01-04 is Sunday
        assertThat(cron.matches(LocalDateTime.of(2026, 1, 4, 9, 0))).isFalse();
    }

    @Test
    void nextFireTime() {
        CronExpression cron = new CronExpression("30 9 * * *");
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime next = cron.nextFireTime(from);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 30));
    }

    @Test
    void nextFireTime_rollsToNextDay() {
        CronExpression cron = new CronExpression("0 8 * * *");
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime next = cron.nextFireTime(from);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 1, 2, 8, 0));
    }

    @Test
    void invalidExpression_throws() {
        assertThatThrownBy(() -> new CronExpression("bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 fields");
    }
}
