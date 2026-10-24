package com.stove.order.api.controller;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static com.stove.order.OrderFixtures.product;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.security.CommonSecurityAutoConfiguration;
import com.stove.order.config.RequestJsonConfig;
import com.stove.order.config.SecurityConfig;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.service.CreateOrder;
import com.stove.order.core.service.ReadOrder;
import com.stove.order.core.service.SearchOrders;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@WebMvcTest(OrderController.class)
@ContextConfiguration(classes = {
        OrderController.class,
        SecurityConfig.class,
        RequestJsonConfig.class,
        CommonSecurityAutoConfiguration.class
})
class OrderAccessTest {
    private static final String SUBJECT = "usr_buyer";
    private static final String REQUEST_ID = "11111111-1111-1111-1111-111111111111";
    private static final String ORDER_PATH = "/api/v1/orders/22222222-2222-2222-2222-222222222222";
    private static final String VALID_REQUEST = """
            {"requestId":"11111111-1111-1111-1111-111111111111",
             "productCode":"Game-1","expectedContentVersion":3,"expectedAmount":10000}
            """;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private CreateOrder create;
    @MockitoBean private ReadOrder read;
    @MockitoBean private SearchOrders search;

    @BeforeEach
    void provideValidatedPrincipalsForRouteAndSubjectChecks() {
        // Signature, issuer, audience and expiry validation remain in the real-JWT integration tests.
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String token = call.getArgument(0, String.class);
            List<String> roles = switch (token) {
                case "member" -> List.of("MEMBER");
                case "member-admin" -> List.of("MEMBER", "ADMIN");
                case "creator" -> List.of("CREATOR");
                case "admin" -> List.of("ADMIN");
                case "no-role" -> List.of();
                default -> throw new BadJwtException("유효하지 않은 토큰입니다.");
            };
            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(SUBJECT)
                    .claim("roles", roles)
                    .claim("member_id", 999L)
                    .claim("preferred_username", "spoofed")
                    .build();
        });
    }

    @ParameterizedTest(name = "anonymous {0} {1}")
    @MethodSource("protectedOperations")
    void anonymousCannotExecuteOrderOperations(HttpMethod method, String path) throws Exception {
        mvc.perform(operation(method, path)).andExpect(status().isUnauthorized());

        verifyNoInteractions(create, read, search);
    }

    @ParameterizedTest(name = "{2} cannot {0} {1}")
    @MethodSource("operationsWithoutMemberRole")
    void rolesWithoutMemberCannotExecuteOrderOperations(HttpMethod method, String path, String token)
            throws Exception {
        mvc.perform(operation(method, path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        verifyNoInteractions(create, read, search);
    }

    @Test
    void rejectedBearerTokenDoesNotReachBusinessServices() throws Exception {
        mvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(create, read, search);
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "member-admin"})
    void creationUsesJwtSubjectAndIgnoresForgedOwnerAndFinalTerms(String token) throws Exception {
        OrderRequest input = new OrderRequest(REQUEST_ID, "Game-1", 3, 10000);
        Order created = Order.create(SUBJECT, input, product(), id());
        when(create.execute(SUBJECT, input)).thenReturn(created);
        Map<String, Object> body = Map.of(
                "requestId", REQUEST_ID, "productCode", "Game-1",
                "expectedContentVersion", 3, "expectedAmount", 10000,
                "buyerSubject", "spoofed", "memberId", 999,
                "status", "PAID", "amount", 1, "currency", "USD");

        mvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(created.getOrderNo()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.amount").value(10000));

        verify(create).execute(eq(SUBJECT), eq(input));
        verifyNoInteractions(read, search);
    }

    @Test
    void listUsesJwtSubjectAndDefaultPagination() throws Exception {
        when(search.execute(SUBJECT, 0, 20)).thenReturn(Page.empty(PageRequest.of(0, 20)));

        mvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer member"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));

        verify(search).execute(SUBJECT, 0, 20);
        verifyNoInteractions(create, read);
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "member-admin"})
    void listIgnoresAForgedBuyerParameterAndForwardsRequestedPagination(String token) throws Exception {
        when(search.execute(SUBJECT, 2, 5)).thenReturn(Page.empty(PageRequest.of(2, 5)));

        mvc.perform(get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("buyerSubject", "spoofed")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2));

        verify(search).execute(SUBJECT, 2, 5);
        verifyNoInteractions(create, read);
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "member-admin"})
    void detailScopesTheLookupToJwtSubjectAndDoesNotExposeInternalIdentifiers(String token) throws Exception {
        Order own = order();
        when(read.execute(own.getOrderNo(), SUBJECT)).thenReturn(own);

        mvc.perform(get("/api/v1/orders/" + own.getOrderNo())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("buyerSubject", "spoofed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(own.getOrderNo()))
                .andExpect(jsonPath("$.data.buyerSubject").doesNotExist())
                .andExpect(jsonPath("$.data.requestEventId").doesNotExist())
                .andExpect(jsonPath("$.data.paymentResultEventId").doesNotExist());

        verify(read).execute(own.getOrderNo(), SUBJECT);
        verifyNoInteractions(create, search);
    }

    @ParameterizedTest
    @MethodSource("unsupportedOperations")
    void memberDoesNotGainUnimplementedCancellationOrOperationalAccess(HttpMethod method, String path)
            throws Exception {
        mvc.perform(operation(method, path).header(HttpHeaders.AUTHORIZATION, "Bearer member"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(create, read, search);
    }

    private static MockHttpServletRequestBuilder operation(HttpMethod method, String path) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path);
        if (method == HttpMethod.POST) {
            request.contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST);
        }
        return request;
    }

    static Stream<Arguments> protectedOperations() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/orders"),
                Arguments.of(HttpMethod.GET, "/api/v1/orders"),
                Arguments.of(HttpMethod.GET, ORDER_PATH));
    }

    static Stream<Arguments> unsupportedOperations() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, ORDER_PATH + "/cancel"),
                Arguments.of(HttpMethod.GET, "/api/v1/ops/dlt"));
    }

    static Stream<Arguments> operationsWithoutMemberRole() {
        return Stream.of("creator", "admin", "no-role").flatMap((String token) -> Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/orders", token),
                Arguments.of(HttpMethod.GET, "/api/v1/orders", token),
                Arguments.of(HttpMethod.GET, ORDER_PATH, token)));
    }
}
