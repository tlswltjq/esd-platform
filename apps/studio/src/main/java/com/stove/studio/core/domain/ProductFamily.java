package com.stove.studio.core.domain;

import java.util.List;

public record ProductFamily(GameProject product, GameProject parent,
                            List<GameProject> children, List<GameProject> components,
                            List<GameProject> bundles) {
}
