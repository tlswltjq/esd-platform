package com.stove.gateway.config;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class StudioPortalConfig {

    @Bean
    RouterFunction<ServerResponse> studioPortalIndex() {
        return route(GET("/studio").or(GET("/studio/")),
                request -> ServerResponse.temporaryRedirect(URI.create("/studio/index.html")).build());
    }
}
