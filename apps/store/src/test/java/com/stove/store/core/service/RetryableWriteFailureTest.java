package com.stove.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;

class RetryableWriteFailureTest {

    private static LockAcquisitionException deadlock() {
        return new LockAcquisitionException("could not execute statement",
                new SQLException("Deadlock found when trying to get lock", "40001", 1213));
    }

    private static ConstraintViolationException duplicate() {
        return new ConstraintViolationException("could not execute statement",
                new SQLException("Duplicate entry", "23000", 1062), "PRIMARY");
    }

    @Test
    @DisplayName("[D-047] EntityManager.flush가 교착을 jakarta.persistence 예외로 감싸도 재시도한다")
    void deadlockWrappedByJpaIsRetryable() {
        assertThat(RetryableWriteFailure.retryable(new OptimisticLockException(deadlock()))).isTrue();
        assertThat(RetryableWriteFailure.retryable(new PersistenceException(new RuntimeException(deadlock())))).isTrue();
    }

    @Test
    @DisplayName("[D-047] EntityManager.flush가 중복 키를 감싸도 재시도한다")
    void duplicateWrappedByJpaIsRetryable() {
        assertThat(RetryableWriteFailure.retryable(new PersistenceException(duplicate()))).isTrue();
    }

    @Test
    @DisplayName("기존에 잡던 실패는 그대로 재시도한다")
    void existingFailuresStayRetryable() {
        assertThat(RetryableWriteFailure.retryable(deadlock())).isTrue();
        assertThat(RetryableWriteFailure.retryable(duplicate())).isTrue();
        assertThat(RetryableWriteFailure.retryable(new DataIntegrityViolationException("duplicate"))).isTrue();
        assertThat(RetryableWriteFailure.retryable(new CannotAcquireLockException("deadlock"))).isTrue();
        assertThat(RetryableWriteFailure.retryable(new ConcurrencyFailureException("same product"))).isTrue();
    }

    @Test
    @DisplayName("업무 충돌과 그 밖의 실패는 재시도하지 않는다")
    void otherFailuresAreNotRetryable() {
        assertThat(RetryableWriteFailure.retryable(new BusinessException(ErrorCode.CONFLICT, "code"))).isFalse();
        assertThat(RetryableWriteFailure.retryable(new IllegalStateException("bug"))).isFalse();
        assertThat(RetryableWriteFailure.retryable(new PersistenceException("other"))).isFalse();
        assertThat(RetryableWriteFailure.retryable(new OptimisticLockException("stale version"))).isFalse();
    }
}
