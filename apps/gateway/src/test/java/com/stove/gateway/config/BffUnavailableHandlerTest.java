package com.stove.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.gateway.config.bff.BffUnavailableHandler;
import io.lettuce.core.RedisCommandTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class BffUnavailableHandlerTest {
    @Test
    void mapsRawLettuceTimeoutFromTheSessionRepositoryToUnavailable() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/bff/session"));
        new BffUnavailableHandler().handle(exchange,
                new RedisCommandTimeoutException("HGETALL timeout")).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("SESSION_STORE_UNAVAILABLE");
    }
}
