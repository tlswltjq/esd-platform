package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchPayments {
    private final PaymentRepository payments;

    public Page<Payment> execute(String buyer, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Sort order = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return payments.findByBuyerSubject(buyer, PageRequest.of(page, size, order));
    }
}
