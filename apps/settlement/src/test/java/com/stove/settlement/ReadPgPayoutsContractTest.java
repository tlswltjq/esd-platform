package com.stove.settlement;

import static com.stove.settlement.PgCostContractFixtures.NOW;
import static com.stove.settlement.PgCostContractFixtures.entry;
import static com.stove.settlement.PgCostContractFixtures.sale;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.settlement.core.domain.PgCostPosting;
import com.stove.settlement.core.domain.PgCostPostingRepository;
import com.stove.settlement.core.domain.PgPayoutQuery;
import com.stove.settlement.core.domain.PgPayoutTotals;
import com.stove.settlement.core.domain.PgStatementCheck;
import com.stove.settlement.core.domain.PgStatementEntry;
import com.stove.settlement.core.domain.PgStatementEntryRepository;
import com.stove.settlement.core.domain.PgStatementItemRepository;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.service.ReadPgPayouts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class ReadPgPayoutsContractTest {
    private final PgStatementEntryRepository entries=mock(PgStatementEntryRepository.class);
    private final PgStatementRepository statements=mock(PgStatementRepository.class);
    private final PgCostPostingRepository postings=mock(PgCostPostingRepository.class);
    private final PgStatementItemRepository items=mock(PgStatementItemRepository.class);
    private final ReadPgPayouts read=new ReadPgPayouts(entries,statements,postings,items);
    private final PgPayoutQuery query=new PgPayoutQuery("merchant","LIVE","2026-11");
    @Test void payoutDateViewKeepsItsLinkToTheSeparateBusinessMonthAndShowsCurrentConflicts() {
        PgStatementEntry entry=entry(1,"PAY",10000,-300,-30);PgCostPosting posting=PgCostPosting.record(entry,sale(),null,99L,NOW);
        ReflectionTestUtils.setField(posting,"id",101L);
        when(entries.findByMerchantIdAndEnvironmentAndDueDateGreaterThanEqualAndDueDateLessThan(eq("merchant"),eq("LIVE"),eq(query.start()),eq(query.end()),any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(postings.findByEntryIdIn(anyList())).thenReturn(List.of(posting));when(items.latestForEntries(anyList())).thenReturn(List.of());
        ReadPgPayouts.Item linked=read.items(query,0,20).getContent().getFirst();
        assertThat(linked.businessMonth()).isEqualTo("2026-10");assertThat(linked.reportedPayoutDate().toString()).isEqualTo("2026-11-05");
        assertThat(linked.costPostingId()).isEqualTo(101L);assertThat(linked.reportedNetAmount()).isEqualTo(9670);assertThat(linked.verificationStatementId()).isEqualTo(99L);
        posting.check(new PgStatementCheck("AMBIGUOUS_PAYMENT",PgCostContractFixtures.PAYMENT));
        assertThat(read.items(query,0,20).getContent().getFirst().checkCode()).isEqualTo("AMBIGUOUS_PAYMENT");
    }
    @Test void matchedObservationScopeIsDifferentFromMissingOrEmptyMonthlyEvidence() {
        when(entries.payoutTotals("merchant","LIVE",query.start(),query.end())).thenReturn(new PgPayoutTotals(1,1,10000,-300,-30));
        when(statements.collectedPayoutDays("merchant","LIVE",query.start(),query.end())).thenReturn(1L);
        assertThat(read.summary(query).status()).isEqualTo("MATCHED_REPORTED");
        assertThat(read.summary(query).basis()).isEqualTo("PG_REPORTED");assertThat(read.summary(query).collectedPayoutDayCount()).isEqualTo(1);
        when(entries.payoutTotals("merchant","LIVE",query.start(),query.end())).thenReturn(new PgPayoutTotals(0,0,0,0,0));
        assertThat(read.summary(query).status()).isEqualTo("EMPTY_REPORTED");
        when(statements.collectedPayoutDays("merchant","LIVE",query.start(),query.end())).thenReturn(0L);
        assertThat(read.summary(query).status()).isEqualTo("NO_EVIDENCE");
    }
}
