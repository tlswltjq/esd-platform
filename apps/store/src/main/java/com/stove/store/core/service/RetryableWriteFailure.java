package com.stove.store.core.service;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;

/**
 * 최초 삽입 경합에서 새 트랜잭션으로 다시 시도할 쓰기 실패인지 판단한다.
 *
 * <p>리포지토리 호출과 커밋 시 flush는 Spring이 {@code DataAccessException}으로 바꿔 주지만, 주입받은
 * {@code EntityManager.flush()}는 그 변환을 거치지 않는다. Hibernate는 교착·중복 키를
 * {@code jakarta.persistence} 예외로 감싸 던지므로(교착은 {@code OptimisticLockException}) 원인 사슬까지 본다(D-047).
 * 업무 충돌({@code BusinessException})과 그 밖의 실패는 재시도하지 않는다.
 */
final class RetryableWriteFailure {

    private static final int MAX_CAUSE_DEPTH = 16;

    private RetryableWriteFailure() {
    }

    static boolean retryable(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (insertCollision(cause)) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }

    private static boolean insertCollision(Throwable cause) {
        return cause instanceof DataIntegrityViolationException
                || cause instanceof TransientDataAccessException
                || cause instanceof ConstraintViolationException
                || cause instanceof LockAcquisitionException;
    }
}
