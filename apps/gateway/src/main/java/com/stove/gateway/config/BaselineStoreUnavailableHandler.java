package com.stove.gateway.config;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * 거절 기준 저장소(Redis)에 닿지 못해 Bearer를 판정할 수 없으면 인증을 우회하지 않고 503을 낸다. 웹 BFF의 세션 저장소 장애
 * (R6.3, {@code SESSION_STORE_UNAVAILABLE})와 같은 원칙이다.
 */
@Order(-2)
public class BaselineStoreUnavailableHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        if (!redisUnavailable(exception) || exchange.getResponse().isCommitted()) {
            return Mono.error(exception);
        }
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"error\":\"ACCESS_BASELINE_STORE_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    static boolean redisUnavailable(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisConnectionFailureException || cause instanceof RedisSystemException
                    || cause instanceof RedisCommandTimeoutException || cause instanceof RedisConnectionException
                    || cause instanceof QueryTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
