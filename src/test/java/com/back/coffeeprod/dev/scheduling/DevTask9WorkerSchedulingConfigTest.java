package com.back.coffeeprod.dev.scheduling;

import com.back.coffeeprod.domain.payment.service.PaymentRecoveryScheduler;
import com.back.coffeeprod.domain.payment.service.PaymentWorkflow;
import com.back.coffeeprod.domain.statistics.repository.SalesRebuildRequestRepository;
import com.back.coffeeprod.domain.statistics.scheduler.SalesRebuildScheduler;
import com.back.coffeeprod.domain.statistics.scheduler.SalesStatisticsScheduler;
import com.back.coffeeprod.domain.statistics.service.SalesRebuildService;
import com.back.coffeeprod.domain.statistics.service.SalesStatisticsService;
import com.back.coffeeprod.global.config.WorkerSchedulingConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.SchedulingAwareRunnable;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DevTask9WorkerSchedulingConfigTest {
    @Configuration(proxyBeanMethods = false) @EnableScheduling
    static class SchedulingEnabled {}

    @ParameterizedTest
    @ValueSource(strings = {"missing", "true", "false"})
    void routingConditionsDefaultsAndOwnedExecutorShutdown(String workers) throws Exception {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(SchedulingEnabled.class, WorkerSchedulingConfig.class,
                        PaymentRecoveryScheduler.class, SalesRebuildScheduler.class, SalesStatisticsScheduler.class)
                .withBean(PaymentWorkflow.class, () -> mock(PaymentWorkflow.class))
                .withBean(SalesRebuildRequestRepository.class, () -> mock(SalesRebuildRequestRepository.class))
                .withBean(SalesRebuildService.class, () -> mock(SalesRebuildService.class))
                .withBean(SalesStatisticsService.class, () -> mock(SalesStatisticsService.class));
        if (!workers.equals("missing")) runner = runner.withPropertyValues("app.payment.workers-enabled=" + workers);
        var executors = new ArrayList<ScheduledThreadPoolExecutor>();
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var payment = context.getBean("paymentRecoveryTaskScheduler", ThreadPoolTaskScheduler.class);
            var statistics = context.getBean("salesStatisticsTaskScheduler", ThreadPoolTaskScheduler.class);
            assertNotSame(payment.getScheduledExecutor(), statistics.getScheduledExecutor());
            for (var scheduler : new ThreadPoolTaskScheduler[]{payment, statistics}) {
                var executor = scheduler.getScheduledThreadPoolExecutor(); executors.add(executor);
                assertEquals(1, executor.getCorePoolSize()); assertTrue(executor.getRemoveOnCancelPolicy());
            }
            assertEquals("payment-recovery-", payment.getThreadNamePrefix());
            assertEquals("sales-statistics-", statistics.getThreadNamePrefix());
            boolean enabled = !workers.equals("false");
            assertEquals(enabled, context.getBeansOfType(PaymentRecoveryScheduler.class).size() == 1);
            assertEquals(enabled, context.getBeansOfType(SalesRebuildScheduler.class).size() == 1);
            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
            assertEquals(enabled ? 3 : 1, tasks.size());
            var routing = tasks.stream().map(task -> assertInstanceOf(SchedulingAwareRunnable.class, task.getTask().getRunnable()))
                    .collect(Collectors.groupingBy(SchedulingAwareRunnable::getQualifier, Collectors.counting()));
            assertEquals(enabled ? Map.of("paymentRecoveryTaskScheduler", 1L, "salesStatisticsTaskScheduler", 2L)
                    : Map.of("salesStatisticsTaskScheduler", 1L), routing);
        });
        assertTrue(executors.stream().allMatch(ScheduledThreadPoolExecutor::isShutdown));
        assertEquals("${app.payment.recovery-delay:30000}", PaymentRecoveryScheduler.class.getMethod("recover").getAnnotation(Scheduled.class).fixedDelayString());
        assertEquals("paymentRecoveryTaskScheduler", PaymentRecoveryScheduler.class.getMethod("recover").getAnnotation(Scheduled.class).scheduler());
        assertEquals("${app.statistics.rebuild-delay:30000}", SalesRebuildScheduler.class.getMethod("rebuild").getAnnotation(Scheduled.class).fixedDelayString());
        assertEquals("salesStatisticsTaskScheduler", SalesRebuildScheduler.class.getMethod("rebuild").getAnnotation(Scheduled.class).scheduler());
        var daily = SalesStatisticsScheduler.class.getMethod("aggregateYesterdaySales").getAnnotation(Scheduled.class);
        assertEquals("0 10 0 * * *", daily.cron()); assertEquals("Asia/Seoul", daily.zone());
        assertEquals("salesStatisticsTaskScheduler", daily.scheduler());
    }
}
