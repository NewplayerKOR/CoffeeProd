package com.back.coffeeprod.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class WorkerSchedulingConfig {
    @Bean
    public ThreadPoolTaskScheduler paymentRecoveryTaskScheduler() {
        return scheduler("payment-recovery-");
    }

    @Bean
    public ThreadPoolTaskScheduler salesStatisticsTaskScheduler() {
        return scheduler("sales-statistics-");
    }

    private ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
