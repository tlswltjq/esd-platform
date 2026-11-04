package com.stove.store.api.controller;

import com.stove.common.kafka.contract.genre.GenreCatalog;
import com.stove.common.kafka.contract.genre.GenreDefinition;
import com.stove.common.web.response.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GenreController {
    @GetMapping("/api/v1/storefront/genres")
    public ApiResponse<List<GenreDefinition>> list() {
        return ApiResponse.ok(GenreCatalog.standard().definitions());
    }
}
