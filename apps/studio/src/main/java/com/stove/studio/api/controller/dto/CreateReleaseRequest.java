package com.stove.studio.api.controller.dto;

import java.time.Instant;

public record CreateReleaseRequest(Instant publishAt) {
}
