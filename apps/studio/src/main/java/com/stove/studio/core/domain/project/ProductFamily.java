package com.stove.studio.core.domain.project;

import java.util.List;

/**
 * 한 프로젝트의 원본·파생 상품과 번들 구성 관계 조회 결과.
 *
 * @param product 관계를 조회하는 기준 게임 프로젝트.
 * @param parent 기준 프로젝트가 파생된 원본 프로젝트.
 * @param children 기준 프로젝트에서 파생된 프로젝트 목록.
 * @param components 기준 번들에 포함된 프로젝트 목록.
 * @param bundles 기준 프로젝트를 구성품으로 포함하는 번들 목록.
 */
public record ProductFamily(GameProject product, GameProject parent,
                            List<GameProject> children, List<GameProject> components,
                            List<GameProject> bundles) {
}
