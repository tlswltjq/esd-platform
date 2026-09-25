package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CiOidcExchangeRequest(@NotNull Long gameId,
                                    @NotBlank @Size(max = 30) String provider,
                                    @NotBlank @Size(max = 30) String platform,
                                    @NotBlank @Size(max = 10000) String oidcToken) {
}
