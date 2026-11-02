package com.stove.settlement.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.settlement.api.controller.dto.SettlementPage;
import com.stove.settlement.core.domain.PgPayoutQuery;
import com.stove.settlement.core.service.ReadPgPayouts;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/settlements/pg-payouts")
public class PgPayoutController {
    private final ReadPgPayouts read;
    @GetMapping public ApiResponse<ReadPgPayouts.Summary> summary(@RequestParam String merchantId,@RequestParam String environment,@RequestParam String month) {
        return ApiResponse.ok(read.summary(new PgPayoutQuery(merchantId,environment,month)));
    }
    @GetMapping("/items") public ApiResponse<SettlementPage<ReadPgPayouts.Item>> items(@RequestParam String merchantId,
            @RequestParam String environment,@RequestParam String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(read.items(new PgPayoutQuery(merchantId,environment,month),page,size)));
    }
}
