package com.stove.payment.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.domain.PgDecline;
import com.stove.payment.core.domain.SimulatedPgTransaction;
import com.stove.payment.core.domain.SimulatedPgTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 모의 공급자의 승인 사실을 보존한다. 요청 간 중복·충돌은 거래 행 잠금으로 직렬화한다. */
@Service
@Profile("!prod & (demo | test)")
@RequiredArgsConstructor
public class SimulatedPgService {

    private final SimulatedPgTransactionRepository transactions;

    @Transactional(readOnly = true)
    public SimulatedPgTransaction get(String pgTxId) {
        return transactions.findById(pgTxId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional
    public PgApproval approve(String pgTxId) {
        return require(pgTxId).approve();
    }

    @Transactional
    public PgDecline decline(String pgTxId, String reasonCode, String reason) {
        return require(pgTxId).decline(reasonCode, reason, false);
    }

    @Transactional
    public PgDecline timeout(String pgTxId) {
        return require(pgTxId).decline("PG_TIMEOUT", "시뮬레이터 결제 시간 초과", true);
    }

    private SimulatedPgTransaction require(String pgTxId) {
        return transactions.findForUpdate(pgTxId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}
