package com.stove.gateway.config;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class P0LabConfig {

    @Bean
    RouterFunction<ServerResponse> p0LabIndex() {
        return route(GET("/p0-lab").or(GET("/p0-lab/")),
                request -> ServerResponse.temporaryRedirect(URI.create("/p0-lab/index.html")).build());
    }
}
