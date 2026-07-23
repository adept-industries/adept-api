package com.adept.api.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;

@Testcontainers
@SpringBootTest(properties = {
    "app.frontend-base-url=http://localhost:3000",
    "app.public-api-base-url=http://localhost:8080",
    "app.email-from=Adept Test <test@adept.local>",
    "app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "app.token-hash-pepper-base64=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
    "app.integration-encryption.active-key-version=1",
    "app.integration-encryption.keys[1]=CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=",
    "app.github.enabled=false",
    "app.jira.enabled=false",
    "app.engine.base-url=http://localhost:8000",
    "app.engine.internal-token=test-only-engine-token",
    "spring.mail.host=localhost",
    "spring.mail.port=1025"
})
class ProcessingJobClaimTest {

    @Container
    static PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("adept_job_claim_test")
            .withUsername("adept")
            .withPassword("adept");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    ProcessingJobRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearJobs() {
        repository.deleteAllInBatch();
    }

    @Test
    void twoTransactionsLockDifferentJobsWithoutWaitingForTheSameRow() throws Exception {
        ProcessingJob first = pendingJob(10);
        ProcessingJob second = pendingJob(20);
        repository.saveAllAndFlush(List.of(first, second));

        CountDownLatch firstWorkerHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<UUID> firstClaim = executor.submit(() ->
                inTransaction(() -> {
                    ProcessingJob job = repository.lockClaimableJobs(1).getFirst();
                    job.setStatus(ProcessingJobStatus.RUNNING);
                    job.setLockedBy("api-test-worker-1");
                    repository.flush();
                    firstWorkerHasLock.countDown();
                    assertThat(releaseFirstWorker.await(10, TimeUnit.SECONDS)).isTrue();
                    return job.getId();
                })
            );

            assertThat(firstWorkerHasLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<UUID> secondClaim = executor.submit(() ->
                inTransaction(() -> {
                    ProcessingJob job = repository.lockClaimableJobs(1).getFirst();
                    job.setStatus(ProcessingJobStatus.RUNNING);
                    job.setLockedBy("api-test-worker-2");
                    repository.flush();
                    return job.getId();
                })
            );

            UUID secondId = secondClaim.get(10, TimeUnit.SECONDS);
            releaseFirstWorker.countDown();
            UUID firstId = firstClaim.get(10, TimeUnit.SECONDS);

            assertThat(Set.of(firstId, secondId))
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        } finally {
            releaseFirstWorker.countDown();
        }
    }

    private ProcessingJob pendingJob(int priority) {
        ProcessingJob job = new ProcessingJob();
        job.setJobType(ProcessingJobType.RECALCULATE_METRICS);
        job.setPriority(priority);
        job.setAvailableAt(Instant.now().minusSeconds(5));
        return job;
    }

    private <T> T inTransaction(ThrowingSupplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout((int) Duration.ofSeconds(15).toSeconds());
        return transaction.execute(status -> {
            try {
                return work.get();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
