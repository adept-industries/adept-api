package com.adept.api.mail;

import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class MailAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(MailAsyncConfig.class);

    @Bean(name = "accountMailExecutor")
    ThreadPoolTaskExecutor accountMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("account-mail-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) -> {
            log.warn(
                "account_mail_rejected active={} queued={}",
                threadPoolExecutor.getActiveCount(),
                threadPoolExecutor.getQueue().size()
            );
            new ThreadPoolExecutor.DiscardPolicy().rejectedExecution(runnable, threadPoolExecutor);
        });
        executor.initialize();
        return executor;
    }
}
