package com.stove.settlement.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.settlement.api.controller.dto.PgStatementItemResponse;
import com.stove.settlement.api.controller.dto.PgStatementResponse;
import com.stove.settlement.api.controller.dto.SettlementPage;
import com.stove.settlement.core.domain.PgStatementPage;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.service.ReceivePgStatement;
import com.stove.settlement.core.service.ReadPgStatements;
import com.stove.settlement.core.service.ReconcilePgStatement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/settlements/pg-statements")
public class PgStatementController {
    private final ReceivePgStatement collect;
    private final ReadPgStatements read;
    private final ReconcilePgStatement reconcile;
    @PostMapping public ApiResponse<PgStatementResponse> collect(@RequestBody PgStatementQuery query,@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(PgStatementResponse.from(collect.execute(query,jwt.getSubject())));
    }
    @GetMapping public ApiResponse<SettlementPage<PgStatementResponse>> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(read.execute(page,size).map(PgStatementResponse::from)));
    }
    @GetMapping("/{id}") public ApiResponse<PgStatementResponse> detail(@PathVariable Long id) {return ApiResponse.ok(PgStatementResponse.from(read.execute(id)));}
    @GetMapping("/{id}/items") public ApiResponse<SettlementPage<PgStatementItemResponse>> items(@PathVariable Long id,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(read.items(id,page,size).map(PgStatementItemResponse::from)));
    }
    @GetMapping("/{id}/pages/{position}") public ApiResponse<PgStatementPage> page(@PathVariable Long id,@PathVariable int position) {
        return ApiResponse.ok(read.page(id,position));
    }
    @PostMapping("/{id}/reconcile") public ApiResponse<PgStatementResponse> reconcile(@PathVariable Long id) {
        return ApiResponse.ok(PgStatementResponse.from(reconcile.execute(id)));
    }
}
