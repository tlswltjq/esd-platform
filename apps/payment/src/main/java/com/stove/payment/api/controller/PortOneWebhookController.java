package com.stove.payment.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.api.listener.PortOneWebhookMapper;
import com.stove.payment.core.domain.PortOneWebhookNotice;
import com.stove.payment.core.service.ReceivePortOneWebhook;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "stove.payment.pg.provider", havingValue = "PORTONE_TEST")
public class PortOneWebhookController {
    private final ReceivePortOneWebhook receive;
    private final ObjectMapper mapper;

    public PortOneWebhookController(ReceivePortOneWebhook receive, ObjectMapper mapper) {
        this.receive = receive;
        this.mapper = mapper;
    }

    @PostMapping(path = "/api/v1/payments/webhooks/portone", consumes = "application/json")
    public ResponseEntity<Void> receive(@RequestBody byte[] body, @RequestHeader("webhook-id") String messageId,
            @RequestAttribute("payment.portone.expected-store") String expectedStoreId) {
        Optional<PortOneWebhookNotice> notice = new PortOneWebhookMapper(mapper, expectedStoreId).from(body, messageId);
        if (notice.isPresent()) receive.execute(notice.get());
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> invalidInput() { return ResponseEntity.badRequest().build(); }
}
