package com.stove.settlement;

import static com.stove.settlement.PgCostContractFixtures.NOW;
import static com.stove.settlement.PgCostContractFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.PgCostContractFixtures.Scenario;
import com.stove.settlement.core.domain.PgCostPosting;
import com.stove.settlement.core.domain.PgStatement;
import com.stove.settlement.core.domain.PgStatementScope;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Repository doubles exercise service decisions; these tests do not prove SQL/transaction behavior. */
class ReconcilePgStatementContractTest {
    @Test void aVerifiedProjectionLinksTheStoredBusinessMonthAndPreservesThePgPayoutDate() {
        Scenario scenario=new Scenario(entry(1,"PAY",10000,-300,-30));
        assertThat(scenario.execute().getStatus()).isEqualTo("MATCHED_REPORTED");
        PgCostPosting posting=scenario.ledger.get(1L);
        assertThat(posting.getBusinessMonth()).isEqualTo("2026-10");
        assertThat(posting.getEntry().getDueDate().toString()).isEqualTo("2026-11-05");
        assertThat(posting.getPolicyId()).isEqualTo("LINKED_BUSINESS_MONTH_V1");
    }
    @Test void repeatedObservationKeepsTheOriginalLinkAndFirstVerificationTime() {
        Scenario scenario=new Scenario(entry(1,"PAY",10000,-300,-30));scenario.execute();
        PgCostPosting first=scenario.ledger.get(1L);
        when(scenario.clock.instant()).thenReturn(NOW.plusSeconds(3600));scenario.execute();
        assertThat(scenario.ledger).hasSize(1);assertThat(scenario.ledger.get(1L)).isSameAs(first);
        assertThat(first.getVerifiedAt()).isEqualTo(NOW);assertThat(first.getVerificationStatementId()).isEqualTo(1L);
    }
    @Test void refundRowsWaitForFullCoverageAndAnOlderRowIsLinkedWhenCoverageCompletes() {
        Scenario scenario=new Scenario(entry(1,"REFUND",-4000,100,10));scenario.withRefund();
        scenario.related.add(entry(2,"PAY",10000,-300,-30));
        assertThat(scenario.execute().getStatus()).isEqualTo("PENDING");assertThat(scenario.ledger.keySet()).containsExactly(2L);
        scenario.related.add(entry(3,"REFUND",-6000,100,10));
        assertThat(scenario.execute().getStatus()).isEqualTo("MATCHED_REPORTED");assertThat(scenario.ledger).hasSize(3);
        assertThat(scenario.ledger.get(1L).getBusinessMonth()).isEqualTo("2026-11");
        assertThat(scenario.ledger.get(3L).getRefundRecordId()).isEqualTo(20L);
        long returned=scenario.ledger.values().stream().filter(posting -> posting.getRefundRecordId()!=null)
                .mapToLong(posting -> posting.getEntry().getFeeVatSum()).sum();
        assertThat(returned).isEqualTo(220);scenario.execute();assertThat(scenario.ledger).hasSize(3);
    }
    @Test void anAbsentRefundStaysPendingAndAnExplicitZeroReturnCanBeLinked() {
        Scenario scenario=new Scenario(entry(1,"REFUND",-10000,0,0));
        assertThat(scenario.execute().getStatus()).isEqualTo("PENDING");assertThat(scenario.ledger).isEmpty();
        scenario.withRefund();assertThat(scenario.execute().getStatus()).isEqualTo("MATCHED_REPORTED");
        assertThat(scenario.ledger.get(1L).getEntry().getFeeVatSum()).isZero();
    }
    @Test void missingPurchaseProjectionStaysPendingUntilItsExistingEvidenceIsAvailable() {
        Scenario scenario=new Scenario(entry(1,"PAY",10000,-300,-30));
        when(scenario.sales.findByPaymentIdIn(anyList())).thenReturn(List.of());
        assertThat(scenario.execute().getStatus()).isEqualTo("PENDING");assertThat(scenario.ledger).isEmpty();
        when(scenario.sales.findByPaymentIdIn(anyList())).thenReturn(List.of(scenario.sale));
        assertThat(scenario.execute().getStatus()).isEqualTo("MATCHED_REPORTED");assertThat(scenario.ledger).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"PAY","REFUND"})
    void conflictingProjectionSuspendsConfirmationButPreservesOriginalLinkAndAudit(String type) {
        boolean pay="PAY".equals(type);
        Scenario scenario=new Scenario(entry(1,type,pay?10000:-10000,pay?-300:200,pay?-30:20));if(!pay) scenario.withRefund();
        scenario.execute();PgCostPosting first=scenario.ledger.get(1L);
        scenario.related.add(entry(2,type,pay?10000:-1,pay?-300:0,pay?-30:0));
        when(scenario.clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertThat(scenario.execute().getStatus()).isEqualTo("MISMATCH");assertThat(scenario.ledger).hasSize(1);
        assertThat(first.getCheckCode()).isEqualTo(pay?"AMBIGUOUS_PAYMENT":"EXCESS_REFUND");
        assertThat(first.getVerifiedAt()).isEqualTo(NOW);assertThat(first.getBusinessMonth()).isEqualTo(pay?"2026-10":"2026-11");
    }
    @Test void anUnverifiedScopeObservationCannotEraseAPreviouslyVerifiedLink() {
        Scenario scenario=new Scenario(entry(1,"PAY",10000,-300,-30));scenario.execute();
        PgStatement weak=PgCostContractFixtures.observation(scenario.related.getFirst(),new PgStatementScope("merchant","LIVE","store","channel",false));
        when(scenario.statements.lock(1L)).thenReturn(Optional.of(weak));
        assertThat(scenario.execute().getStatus()).isEqualTo("PENDING");
        assertThat(scenario.ledger.get(1L).getCheckCode()).isEqualTo("MATCHED_REPORTED");
    }
    @Test void inconsistentApprovalEvidenceStopsBeforeAnyCostLinkIsWritten() {
        Scenario scenario=new Scenario(entry(1,"PAY",10000,-300,-30));
        doThrow(new BusinessException(ErrorCode.CONFLICT)).when(scenario.fact).requireSale(scenario.sale);
        assertThatThrownBy(scenario::execute).isInstanceOf(BusinessException.class);assertThat(scenario.ledger).isEmpty();
    }
}
