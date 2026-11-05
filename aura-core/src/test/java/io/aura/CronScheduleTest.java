package io.aura;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronScheduleTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter WALL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Test
    void minuteStepFiresOnTheNextBoundary() {
        assertThat(next("*/15 * * * *", 2026, 11, 3, 14, 7)).isEqualTo("2026-11-03 14:15");
        assertThat(next("*/15 * * * *", 2026, 11, 3, 14, 15)).isEqualTo("2026-11-03 14:30");
    }

    @Test
    void dailyFireRollsOverToTomorrowAndSecondsAreIgnored() {
        assertThat(next("0 9 * * *", 2026, 11, 3, 14, 7)).isEqualTo("2026-11-04 09:00");
        assertThat(nextAtSecond("0 9 * * *", 2026, 11, 3, 9, 0, 30)).isEqualTo("2026-11-04 09:00");
    }

    @Test
    void listsAndRangesBothNarrowAField() {
        assertThat(next("0,30 8-10 * * *", 2026, 11, 3, 7, 59)).isEqualTo("2026-11-03 08:00");
        assertThat(next("0,30 8-10 * * *", 2026, 11, 3, 8, 0)).isEqualTo("2026-11-03 08:30");
        assertThat(next("0,30 8-10 * * *", 2026, 11, 3, 10, 30)).isEqualTo("2026-11-04 08:00");
    }

    @Test
    void stepsRunInsideTheirRangeAndToTheEndOfTheFieldWhenUnbounded() {
        assertThat(next("5-40/10 * * * *", 2026, 11, 3, 14, 4)).isEqualTo("2026-11-03 14:05");
        assertThat(next("5-40/10 * * * *", 2026, 11, 3, 14, 5)).isEqualTo("2026-11-03 14:15");
        assertThat(next("5-40/10 * * * *", 2026, 11, 3, 14, 36)).isEqualTo("2026-11-03 15:05");
        assertThat(next("20/5 * * * *", 2026, 11, 3, 14, 19)).isEqualTo("2026-11-03 14:20");
        assertThat(next("20/5 * * * *", 2026, 11, 3, 14, 55)).isEqualTo("2026-11-03 15:20");
    }

    @Test
    void dayOfWeekUsesCronNumberingAndSpellsSundayBothWays() {
        // 2026-11-03 is a Tuesday.
        assertThat(next("0 0 * * 1", 2026, 11, 3, 1, 0)).isEqualTo("2026-11-09 00:00");
        assertThat(next("0 0 * * 0", 2026, 11, 3, 1, 0)).isEqualTo("2026-11-08 00:00");
        assertThat(next("0 0 * * 7", 2026, 11, 3, 1, 0)).isEqualTo("2026-11-08 00:00");
    }

    @Test
    void restrictedDayOfMonthAndDayOfWeekFormAUnion() {
        // Either the 13th or any Friday fires, which is what a crontab line does.
        assertThat(next("0 0 13 * 5", 2026, 11, 3, 1, 0)).isEqualTo("2026-11-06 00:00");
        assertThat(next("0 0 13 * 5", 2026, 11, 6, 1, 0)).isEqualTo("2026-11-13 00:00");
    }

    @Test
    void aLeapDayScheduleWaitsForTheNextLeapYear() {
        assertThat(next("0 0 29 2 *", 2026, 11, 3, 1, 0)).isEqualTo("2028-02-29 00:00");
    }

    @Test
    void monthFieldHoldsTheDayBack() {
        assertThat(next("0 0 1 1 *", 2026, 11, 3, 1, 0)).isEqualTo("2027-01-01 00:00");
    }

    @Test
    void aSpringForwardGapShiftsTheFireTimeInsteadOfSkippingTheDay() {
        ZoneId newYork = ZoneId.of("America/New_York");
        // 2027-03-14 is the local spring-forward day: 02:30 does not exist.
        ZonedDateTime next = CronSchedule.parse("30 2 * * *")
                .next(ZonedDateTime.of(LocalDateTime.of(2027, 3, 13, 3, 0), newYork));
        assertThat(next.toLocalDateTime()).isEqualTo(LocalDateTime.of(2027, 3, 14, 3, 30));
        assertThat(next.getOffset()).isEqualTo(java.time.ZoneOffset.ofHours(-4));
    }

    @Test
    void aFallBackOverlapResolvesToThePassTheScheduleWasArmedFrom() {
        ZoneId newYork = ZoneId.of("America/New_York");
        // 2026-11-01 turns 02:00 EDT back to 01:00 EST, so 01:35 is a real local time twice over.
        CronSchedule schedule = CronSchedule.parse("35 * * * *");
        ZonedDateTime firstPass = overlapMinute(newYork, 1, 30).withEarlierOffsetAtOverlap();
        ZonedDateTime secondPass = overlapMinute(newYork, 1, 30).withLaterOffsetAtOverlap();

        assertThat(schedule.next(firstPass))
                .isEqualTo(overlapMinute(newYork, 1, 35).withEarlierOffsetAtOverlap());
        // Asked naively, the second pass answers 01:35 EDT: a minute that has already gone by.
        assertThat(schedule.next(secondPass))
                .isEqualTo(overlapMinute(newYork, 1, 35).withLaterOffsetAtOverlap());
    }

    @Test
    void noFireTimeEverLandsAtOrBeforeTheTimeItWasComputedFrom() {
        ZoneId newYork = ZoneId.of("America/New_York");
        ZonedDateTime cursor = ZonedDateTime.of(LocalDateTime.of(2026, 11, 1, 0, 30), newYork);
        for (String expression : java.util.List.of("* * * * *", "*/5 * * * *", "35 * * * *",
                "30 1 * * *")) {
            CronSchedule schedule = CronSchedule.parse(expression);
            for (int minute = 0; minute < 180; minute++) {
                assertThat(schedule.next(cursor).isAfter(cursor))
                        .as("%s armed from %s", expression, cursor)
                        .isTrue();
                cursor = cursor.plusMinutes(1);
            }
        }
    }

    @Test
    void rejectsAnExpressionThatIsNotFiveFields() {
        assertRejected("* * * *", "needs 5 fields");
        assertRejected("   ", "needs 5 fields");
        assertRejected(null, "needs 5 fields");
    }

    @Test
    void rejectsEachFieldOutsideItsOwnRange() {
        assertRejected("60 * * * *", "minute field \"60\"");
        assertRejected("60 * * * *", "between 0 and 59");
        assertRejected("0 24 * * *", "hour field \"24\"");
        assertRejected("0 0 0 * *", "day-of-month field \"0\"");
        assertRejected("0 0 * 13 *", "month field \"13\"");
        assertRejected("0 0 * * 8", "day-of-week field \"8\"");
    }

    @Test
    void rejectsTokensThatAreNotRangesListsOrSteps() {
        assertRejected("abc * * * *", "minute field \"abc\"", "is not a number");
        assertRejected("30-10 * * * *", "minute field \"30-10\"", "runs backwards");
        assertRejected("*/0 * * * *", "minute field \"*/0\"", "step of at least 1");
        assertRejected("1,,2 * * * *", "minute field \"1,,2\"", "empty list item");
        assertRejected("1-2-3 * * * *", "minute field \"1-2-3\"", "is not a number");
    }

    @Test
    void rejectsADayOfMonthThatNoSelectedMonthHas() {
        assertRejected("0 0 31 2 *", "never fires", "day-of-month 31");
        assertRejected("0 0 30 2 *", "never fires", "day-of-month 30");
        assertRejected("0 0 31 4 *", "never fires", "day-of-month 31 does not exist in any selected month");
        assertRejected("0 0 31 2,4 *", "never fires", "longest is month 4 with 30 days");
    }

    @Test
    void onlyANarrowedDayFieldCanBeAnEmptyDay() {
        // An unqualified day-of-month means every day the month has, and an unqualified month
        // leaves the 31-day months, and a restricted day-of-week fires on its own.
        assertThat(next("0 0 * 2 *", 2026, 11, 3, 1, 0)).isEqualTo("2027-02-01 00:00");
        assertThat(next("0 0 31 * *", 2026, 11, 3, 1, 0)).isEqualTo("2026-12-31 00:00");
        assertThat(next("0 0 31 2 1", 2026, 11, 3, 1, 0)).isEqualTo("2027-02-01 00:00");
    }

    @Test
    void stopsSearchingWhenTheScheduleCannotFireInsideTheSearchWindow() {
        // 2100 is not a leap year, so the next 29 February after 2096 is 2104: outside the window.
        ZonedDateTime from = ZonedDateTime.of(LocalDateTime.of(2096, 3, 1, 0, 0), SHANGHAI);
        assertThatThrownBy(() -> CronSchedule.parse("0 0 29 2 *").next(from))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no fire time")
                .hasMessageContaining("0 0 29 2 *");
    }

    /** A local time inside the 2026-11-01 New York overlap, which the zone passes through twice. */
    private static ZonedDateTime overlapMinute(ZoneId zone, int hour, int minute) {
        return ZonedDateTime.of(LocalDateTime.of(2026, 11, 1, hour, minute), zone);
    }

    private static String next(String expression, int year, int month, int day, int hour, int minute) {
        return nextAtSecond(expression, year, month, day, hour, minute, 0);
    }

    private static String nextAtSecond(String expression, int year, int month, int day,
                                       int hour, int minute, int second) {
        ZonedDateTime from = ZonedDateTime.of(
                LocalDateTime.of(year, month, day, hour, minute, second), SHANGHAI);
        return CronSchedule.parse(expression).next(from).format(WALL);
    }

    private static void assertRejected(String expression, String... fragments) {
        assertThatThrownBy(() -> CronSchedule.parse(expression))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(error -> {
                    for (String fragment : fragments) {
                        assertThat(error).hasMessageContaining(fragment);
                    }
                });
    }
}
