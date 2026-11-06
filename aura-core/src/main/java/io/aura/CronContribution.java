package io.aura;

/** One cron contribution: the schedule, the task it runs, and the module that contributed it. */
record CronContribution(CronSchedule schedule, Runnable task, String moduleId) {
    CronContribution {
        java.util.Objects.requireNonNull(schedule, "schedule");
        java.util.Objects.requireNonNull(task, "task");
        java.util.Objects.requireNonNull(moduleId, "moduleId");
    }
}
