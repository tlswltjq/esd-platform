package com.stove.common.contract.payload;

/** Immutable creator instruction; withdrawalId identifies the durable Studio command. */
public record ProjectWithdrawalRequest(
        Long withdrawalId, Long requestId, Long projectId, long contentVersion,
        String productCode, String ownerSubject, String reason) {}
