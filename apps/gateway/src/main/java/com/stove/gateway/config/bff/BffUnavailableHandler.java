package com.stove.gateway.config.bff;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class BffUnavailableHandler implements WebExceptionHandler {
    @Override public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        boolean unavailable = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisConnectionFailureException
                    || cause instanceof RedisSystemException
                    || cause instanceof RedisCommandTimeoutException
                    || cause instanceof RedisConnectionException
                    || cause instanceof QueryTimeoutException) {
                unavailable = true;
                break;
            }
        }
        if (!unavailable || exchange.getResponse().isCommitted()) return Mono.error(exception);
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"error\":\"SESSION_STORE_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
