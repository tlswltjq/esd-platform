package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.InternalTesterGrant;
import com.stove.studio.core.domain.ReleaseChannel;

public record TesterGrantResponse(Long grantId, Long gameId, String testerSubject,
                                  ReleaseChannel channel, boolean active) {
    public static TesterGrantResponse from(InternalTesterGrant value) {
        return new TesterGrantResponse(value.getId(), value.getGameId(), value.getTesterSubject(),
                value.getChannel(), value.isActive());
    }
}
