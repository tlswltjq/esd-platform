package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.GameProject;
import java.util.List;

public record ProductFamilyResponse(ProjectResponse product, ProjectResponse parent,
                                    List<ProjectResponse> children,
                                    List<ProjectResponse> bundleComponents,
                                    List<ProjectResponse> bundles) {
    public static ProductFamilyResponse from(GameProject product, GameProject parent,
                                             List<GameProject> children, List<GameProject> components,
                                             List<GameProject> bundles) {
        return new ProductFamilyResponse(ProjectResponse.from(product),
                parent == null ? null : ProjectResponse.from(parent),
                children.stream().map(ProjectResponse::from).toList(),
                components.stream().map(ProjectResponse::from).toList(),
                bundles.stream().map(ProjectResponse::from).toList());
    }
}
