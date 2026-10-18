package com.stove.mockpg.api.controller;

import com.stove.mockpg.core.domain.Charge;
import com.stove.mockpg.core.domain.ChargeRequest;
import com.stove.mockpg.core.domain.ProviderException;
import com.stove.mockpg.core.domain.SettlementReport;
import com.stove.mockpg.core.service.ProcessProviderLedger;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProviderController {
    private final ProcessProviderLedger ledger;
    public ProviderController(ProcessProviderLedger ledger) { this.ledger = ledger; }

    @PostMapping("/v1/charges")
    Charge charge(@RequestHeader("Idempotency-Key") String key, @RequestBody ChargeRequest request) {
        if (!key.equals(request.paymentId())) throw new IllegalArgumentException("Idempotency key must match payment reference");
        return ledger.charge(request);
    }

    @GetMapping("/v1/charges/by-reference/{paymentId}")
    Charge charge(@PathVariable String paymentId) { return ledger.readCharge(paymentId); }

    @GetMapping("/v1/settlements/{id}")
    SettlementReport report(@PathVariable String id) { return ledger.readReport(id); }

    public record RunRequest(String requestId) { }

    @PostMapping("/test-support/v1/settlement-runs")
    SettlementReport run(@RequestBody RunRequest request) { return ledger.run(request.requestId()); }

    @GetMapping("/test-support/v1/settlement-runs/{requestId}")
    Map<String, String> run(@PathVariable String requestId) {
        return Map.of("providerSettlementId", ledger.readRun(requestId));
    }

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<Map<String, String>> providerError(ProviderException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    ResponseEntity<Map<String, String>> invalid(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }
}
