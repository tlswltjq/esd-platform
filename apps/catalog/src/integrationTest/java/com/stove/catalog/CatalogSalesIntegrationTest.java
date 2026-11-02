package com.stove.catalog;

import static com.stove.catalog.CatalogFixtures.envelope;
import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.core.domain.SellerSaleChange;
import com.stove.catalog.core.service.ChangeSellerSaleStatus;
import com.stove.catalog.core.service.PublishProjectRelease;
import com.stove.common.kafka.contract.payload.CatalogProductPublishedEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CatalogSalesIntegrationTest extends CatalogIntegrationTestBase {
    @Autowired PublishProjectRelease publish;
    @Autowired ChangeSellerSaleStatus seller;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean OutboxRecorder outbox;
    @LocalServerPort int port;
    @BeforeEach void tokens() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String token=call.getArgument(0,String.class);
            String role="admin".equals(token)?"ADMIN":"member".equals(token)?"MEMBER":"CREATOR";
            return Jwt.withTokenValue(token).header("alg","RS256").subject("owner".equals(token)?"creator":token)
                    .claim("roles",List.of(role)).issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(300)).build();
        });
    }
    record Response(int status,JsonNode body,String cache) { JsonNode data() { return body.path("data"); } }
    private Response call(String method,String path,String token,Object body) {
        RestClient.RequestBodySpec request=RestClient.create("http://127.0.0.1:"+port).method(HttpMethod.valueOf(method)).uri(path);
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((r,s) -> new Response(s.getStatusCode().value(),mapper.readTree(s.getBody()),s.getHeaders().getCacheControl()));
    }
    private String publish() {
        ProjectReleaseRequestedEvent event=event(id(),1);
        publish.execute(ReleaseSubmissionMapper.from(envelope(event),event));
        return event.partitionKey();
    }
    private String own(String code) { return "/api/v1/products/by-code/"+code+"/sales"; }
    private String admin(String code) { return "/api/v1/admin/products/"+code+"/sales/restriction"; }
    private Map<String,Object> state(String status,long version) { return Map.of("saleStatus",status,"expectedCatalogVersion",version); }
    private Map<String,Object> restriction(boolean value,long version) { return Map.of("restricted",value,"expectedCatalogVersion",version); }
    private int changes(String code) { return jdbc.queryForObject("SELECT COUNT(*) FROM catalog_sale_change c JOIN catalog_product p ON c.product_id=p.id WHERE p.product_code=?",Integer.class,code); }
    private int events(String code) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='CatalogProductPublished'",Integer.class,code); }

    @Test void jwtRoleAndImmutableOwnerProtectBothSalesAuthorities() {
        String code=publish();
        assertThat(call("PUT",own(code),null,state("STOPPED",1)).status()).isEqualTo(401);
        assertThat(call("PUT",own(code),"member",state("STOPPED",1)).status()).isEqualTo(403);
        assertThat(call("PUT",own(code),"other",state("STOPPED",1)).status()).isEqualTo(404);
        assertThat(call("PUT",admin(code),"owner",restriction(true,1)).status()).isEqualTo(403);
        assertThat(call("PUT",own(code),"admin",state("STOPPED",1)).status()).isEqualTo(403);
        assertThat(call("GET",own(code),"owner",null).status()).isEqualTo(200);
        assertThat(call("GET",admin(code),"admin",null).status()).isEqualTo(200);
        assertThat(changes(code)).isZero();
    }
    @Test void eachAuthorityCanReleaseOnlyItsOwnConditionAndAllChangesAreAudited() {
        String code=publish();
        assertThat(call("PUT",own(code),"owner",state("STOPPED",1)).data().path("saleStatus").asText()).isEqualTo("STOPPED");
        assertThat(call("PUT",admin(code),"admin",restriction(true,2)).data().path("saleStatus").asText()).isEqualTo("BLOCKED");
        Response resume=call("PUT",own(code),"owner",state("ON_SALE",3));
        assertThat(resume.status()).isEqualTo(200); assertThat(resume.cache()).isEqualTo("no-store");
        assertThat(resume.data().path("saleStatus").asText()).isEqualTo("BLOCKED");
        assertThat(call("PUT",admin(code),"admin",restriction(false,4)).data().path("saleStatus").asText()).isEqualTo("ON_SALE");
        call("PUT",own(code),"owner",state("STOPPED",5));
        call("PUT",admin(code),"admin",restriction(true,6));
        Response released=call("PUT",admin(code),"admin",restriction(false,7));
        assertThat(released.data().path("saleStatus").asText()).isEqualTo("STOPPED");
        assertThat(released.data().path("catalogVersion").asLong()).isEqualTo(8);
        assertThat(changes(code)).isEqualTo(7); assertThat(events(code)).isEqualTo(8);
        assertThat(jdbc.queryForList("SELECT DISTINCT actor_subject FROM catalog_sale_change c JOIN catalog_product p ON c.product_id=p.id WHERE p.product_code=?",String.class,code))
                .containsExactlyInAnyOrder("creator","admin");
        assertThat(call("GET","/api/v1/products/by-code/"+code,null,null).status()).isEqualTo(404);
    }
    @Test void noOpStaleAndMalformedCommandsDoNotCreateExtraChanges() {
        String code=publish();
        assertThat(call("PUT",own(code),"owner",state("ON_SALE",1)).status()).isEqualTo(200);
        assertThat(changes(code)).isZero();
        assertThat(call("PUT",own(code),"owner",state("STOPPED",1)).status()).isEqualTo(200);
        assertThat(call("PUT",own(code),"owner",state("ON_SALE",1)).status()).isEqualTo(409);
        assertThat(call("PUT",own(code),"owner",state("UNKNOWN",2)).status()).isEqualTo(400);
        assertThat(call("PUT",admin(code),"admin",Map.of("expectedCatalogVersion",2)).status()).isEqualTo(400);
        assertThat(changes(code)).isEqualTo(1); assertThat(events(code)).isEqualTo(2);
    }
    @Test void outboxFailureRollsBackTheActualStatusVersionAndAuditAndCanBeRetried() {
        String code=publish();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx ->
                    doThrow(new IllegalStateException("fixture outbox failure")).when(outbox)
                            .record(eq("CatalogProduct"),eq(code),any(CatalogProductPublishedEvent.class)));
            assertThatThrownBy(() -> seller.execute("creator",new SellerSaleChange(code,"STOPPED",1))).isInstanceOf(IllegalStateException.class);
            assertThat(changes(code)).isZero(); assertThat(events(code)).isEqualTo(1);
            assertThat(call("GET",own(code),"owner",null).data().path("catalogVersion").asLong()).isEqualTo(1);
        } finally { reset(outbox); }
        assertThat(seller.execute("creator",new SellerSaleChange(code,"STOPPED",1)).catalogVersion()).isEqualTo(2);
        assertThat(changes(code)).isEqualTo(1);
    }
    @Test void simultaneousCommandsUsingOneVersionCannotOverwriteEachOther() throws Exception {
        String code=publish(); ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Integer> first=pool.submit(() -> changeAtOnce(code,start));
            Future<Integer> second=pool.submit(() -> changeAtOnce(code,start));
            start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(changes(code)).isEqualTo(1); assertThat(events(code)).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }
    private int changeAtOnce(String code,CountDownLatch start) throws Exception {
        start.await();
        try { seller.execute("creator",new SellerSaleChange(code,"STOPPED",1)); return 200; }
        catch(BusinessException conflict) { return conflict.errorCode().statusCode(); }
    }
}
