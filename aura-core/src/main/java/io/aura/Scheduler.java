package io.aura;

import io.aura.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private final ScheduledExecutorService executor;
    private final List<TaskInfo> tasks = new ArrayList<>();

    public Scheduler() {
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aura-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void scan(List<Object> beans) {
        for (Object bean : beans) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                Scheduled ann = m.getAnnotation(Scheduled.class);
                if (ann == null) continue;
                validate(m, ann);
                schedule(bean, m, ann);
            }
        }
    }

    private void validate(Method m, Scheduled ann) {
        if (!Modifier.isPublic(m.getModifiers())) {
            throw new IllegalStateException(
                    "@Scheduled method must be public: " + m.getDeclaringClass().getSimpleName() + "." + m.getName());
        }
        if (m.getParameterCount() > 0) {
            throw new IllegalStateException(
                    "@Scheduled method must have no parameters: " + m.getDeclaringClass().getSimpleName() + "." + m.getName());
        }
        int count = 0;
        if (!ann.cron().isEmpty()) count++;
        if (ann.fixedRate() > 0) count++;
        if (ann.fixedDelay() > 0) count++;
        if (count == 0) {
            throw new IllegalStateException(
                    "@Scheduled must specify cron, fixedRate, or fixedDelay: " + m.getDeclaringClass().getSimpleName() + "." + m.getName());
        }
        if (count > 1) {
            throw new IllegalStateException(
                    "@Scheduled must specify only one of cron/fixedRate/fixedDelay: " + m.getDeclaringClass().getSimpleName() + "." + m.getName());
        }
    }

    private void schedule(Object bean, Method m, Scheduled ann) {
        String className = m.getDeclaringClass().getSimpleName();
        String methodName = className + "." + m.getName();
        m.setAccessible(true);

        if (ann.fixedRate() > 0) {
            tasks.add(new TaskInfo(methodName, "fixedRate=" + ann.fixedRate() + "ms"));
            executor.scheduleAtFixedRate(() -> invoke(bean, m, methodName), ann.fixedRate(), ann.fixedRate(), TimeUnit.MILLISECONDS);
        } else if (ann.fixedDelay() > 0) {
            tasks.add(new TaskInfo(methodName, "fixedDelay=" + ann.fixedDelay() + "ms"));
            executor.scheduleWithFixedDelay(() -> invoke(bean, m, methodName), ann.fixedDelay(), ann.fixedDelay(), TimeUnit.MILLISECONDS);
        } else {
            CronExpression cron = new CronExpression(ann.cron());
            tasks.add(new TaskInfo(methodName, "cron: " + ann.cron()));
            scheduleCron(bean, m, methodName, cron);
        }
    }

    private void scheduleCron(Object bean, Method m, String methodName, CronExpression cron) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = cron.nextFireTime(now);
        long delayMs = Duration.between(now, next).toMillis();
        executor.schedule(() -> {
            invoke(bean, m, methodName);
            scheduleCronFromFire(bean, m, methodName, cron, next);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleCronFromFire(Object bean, Method m, String methodName, CronExpression cron, LocalDateTime lastFire) {
        LocalDateTime next = cron.nextFireTime(lastFire);
        long delayMs = Duration.between(LocalDateTime.now(), next).toMillis();
        if (delayMs < 0) delayMs = 0;
        executor.schedule(() -> {
            invoke(bean, m, methodName);
            scheduleCronFromFire(bean, m, methodName, cron, next);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void invoke(Object bean, Method m, String methodName) {
        try {
            m.invoke(bean);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("[Scheduler] {} failed: {}", methodName, cause.getMessage(), cause);
        }
    }

    public List<TaskInfo> tasks() {
        return tasks;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public record TaskInfo(String method, String schedule) {}
}
