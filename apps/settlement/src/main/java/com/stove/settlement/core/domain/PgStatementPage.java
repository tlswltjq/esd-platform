package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Lob;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PgStatementPage {
    @Column(updatable=false,length=2000) private String requestCursor;
    @Column(nullable=false,updatable=false,length=2000) private String nextCursor;
    @Lob @Column(nullable=false,updatable=false,columnDefinition="LONGTEXT") private String responseBody;
    public PgStatementPage(String requestCursor,String nextCursor,String responseBody) {
        SettlementValues.require((requestCursor==null || requestCursor.length()<=2000) && nextCursor!=null && nextCursor.length()<=2000
                && SettlementValues.text(responseBody,4_000_000),"PG 명세의 페이지 원본이 올바르지 않습니다.");
        this.requestCursor=requestCursor;this.nextCursor=nextCursor;this.responseBody=responseBody;
    }
}
