package com.stove.payment.api.controller.dto;
import com.stove.payment.core.domain.Payment;
import org.springframework.data.domain.Page;
import java.util.List;

public record PaymentPage(List<PaymentResponse> content,int page,int size,long totalElements,int totalPages) {
    public static PaymentPage from(Page<Payment> page) {
        return new PaymentPage(page.map(PaymentResponse::from).getContent(),page.getNumber(),page.getSize(),page.getTotalElements(),page.getTotalPages());
    }
}
