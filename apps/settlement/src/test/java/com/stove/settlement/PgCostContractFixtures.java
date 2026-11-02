package com.stove.settlement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.settlement.core.domain.PgCostPosting;
import com.stove.settlement.core.domain.PgCostPostingRepository;
import com.stove.settlement.core.domain.PgFunding;
import com.stove.settlement.core.domain.PgStatement;
import com.stove.settlement.core.domain.PgStatementEntry;
import com.stove.settlement.core.domain.PgStatementEntryRepository;
import com.stove.settlement.core.domain.PgStatementItem;
import com.stove.settlement.core.domain.PgStatementItemRepository;
import com.stove.settlement.core.domain.PgStatementPage;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.domain.PgStatementReport;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.domain.PgStatementRow;
import com.stove.settlement.core.domain.PgStatementScope;
import com.stove.settlement.core.domain.PgStatementTotals;
import com.stove.settlement.core.domain.PgTransactionRecord;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.service.ReconcilePgStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.test.util.ReflectionTestUtils;

/** Future LIVE read-projection contract doubles, not persisted/admitted LIVE purchases. */
final class PgCostContractFixtures {
    static final Instant NOW=Instant.parse("2026-11-15T03:00:00Z");
    static final String PAYMENT="fixture-payment";
    static final String ORDER="00000000-0000-0000-0000-000000000001";
    static final PgStatementScope SCOPE=new PgStatementScope("merchant","LIVE","store","channel",true);
    private PgCostContractFixtures() { }
    static PgStatementEntry entry(long id,String type,long amount,int fee,int vat) {
        PgStatementRow row=new PgStatementRow(type,"transaction-"+id,"token",PAYMENT,"Fixture title",amount,fee,vat,fee+vat,
                "CARD",LocalDate.of(2026,11,5));
        PgStatementEntry entry=PgStatementEntry.record(SCOPE,row,NOW);
        // Only simulate the identity that a repository would assign; never modify environment or business values.
        ReflectionTestUtils.setField(entry,"id",id);return entry;
    }
    static PgStatement observation(PgStatementEntry entry,PgStatementScope scope) {
        List<PgStatementRow> rows=List.of(entry.value());PgStatementTotals totals=PgStatementTotals.sum(rows);
        PgStatement statement=PgStatement.record(new PgStatementReport(scope,new PgStatementQuery("DUE",entry.getDueDate()),
                List.of(new PgStatementPage(null,"",PgStatementFixtures.page(rows,"",totals).toString())),rows,totals),"fixture",NOW);
        ReflectionTestUtils.setField(statement,"id",1L);return statement;
    }
    static SettlementSale sale() {
        SettlementSale sale=mock(SettlementSale.class);
        when(sale.getId()).thenReturn(10L);when(sale.getOrderNo()).thenReturn(ORDER);when(sale.getPaymentId()).thenReturn(PAYMENT);
        when(sale.getProvider()).thenReturn("PORTONE_LIVE");when(sale.getProviderTransactionId()).thenReturn("token");
        when(sale.getSellerSubject()).thenReturn("seller");when(sale.getSettlementMonth()).thenReturn("2026-10");
        when(sale.getAmount()).thenReturn(10000L);when(sale.getCurrency()).thenReturn("KRW");return sale;
    }
    static SettlementRefund refund() {
        SettlementRefund refund=mock(SettlementRefund.class);
        when(refund.getId()).thenReturn(20L);when(refund.getOrderNo()).thenReturn(ORDER);when(refund.getPaymentId()).thenReturn(PAYMENT);
        when(refund.getProviderTransactionId()).thenReturn("token");when(refund.getSellerSubject()).thenReturn("seller");
        when(refund.getSettlementMonth()).thenReturn("2026-11");when(refund.getAmount()).thenReturn(10000L);return refund;
    }
    static final class Scenario {
        final PgStatementRepository statements=mock(PgStatementRepository.class);
        final PgStatementEntryRepository entries=mock(PgStatementEntryRepository.class);
        final PgStatementItemRepository items=mock(PgStatementItemRepository.class);
        final PgTransactionRepository transactions=mock(PgTransactionRepository.class);
        final SettlementSaleRepository sales=mock(SettlementSaleRepository.class);
        final SettlementRefundRepository refunds=mock(SettlementRefundRepository.class);
        final PgCostPostingRepository postings=mock(PgCostPostingRepository.class);
        final Clock clock=mock(Clock.class);
        final SettlementSale sale=sale();
        final PgTransactionRecord fact=mock(PgTransactionRecord.class);
        final List<PgStatementEntry> related=new ArrayList<>();
        final Map<Long,PgCostPosting> ledger=new LinkedHashMap<>();
        final PgStatement statement;
        final ReconcilePgStatement reconcile;
        Scenario(PgStatementEntry observed) {
            related.add(observed);when(clock.instant()).thenReturn(NOW);
            statement=observation(observed,SCOPE);
            when(statements.lock(1L)).thenReturn(Optional.of(statement));
            when(items.findByStatementIdOrderByItemPosition(1L)).thenReturn(List.of(PgStatementItem.record(1L,0,observed)));
            when(entries.lockRelated(eq("merchant"),eq("LIVE"),anyList())).thenReturn(related);
            when(sales.findByPaymentIdIn(anyList())).thenReturn(List.of(sale));
            PgFunding funding=mock(PgFunding.class);
            when(funding.getProviderStoreId()).thenReturn("store");when(funding.getProviderChannelKey()).thenReturn("channel");
            when(funding.getProviderChannelType()).thenReturn("LIVE");
            when(fact.getProvider()).thenReturn("PORTONE_LIVE");when(fact.getProviderTransactionId()).thenReturn("token");
            when(fact.getPaymentId()).thenReturn(PAYMENT);when(fact.getFunding()).thenReturn(funding);
            when(transactions.findByPaymentIdIn(anyList())).thenReturn(List.of(fact));
            when(refunds.findByOrderNoIn(anyList())).thenReturn(List.of());
            when(postings.findByEntryIdIn(anyList())).thenAnswer(invocation -> List.copyOf(ledger.values()));
            when(postings.save(any(PgCostPosting.class))).thenAnswer(invocation -> {
                PgCostPosting posting=invocation.getArgument(0);ledger.put(posting.getEntry().getId(),posting);return posting;
            });
            reconcile=new ReconcilePgStatement(statements,entries,items,transactions,sales,refunds,clock,postings);
        }
        void withRefund() {
            SettlementRefund refund=refund();when(refunds.findByOrderNoIn(anyList())).thenReturn(List.of(refund));
        }
        PgStatement execute() {return reconcile.execute(1L);}
    }
}
