package com.stove.archfixture.violating.api.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위반 픽스처 — 세 규칙을 동시에 어긴다.
 *
 * <ul>
 *   <li>{@code @RestController} 인데 이름이 {@code Controller} 로 끝나지 않는다</li>
 *   <li>인바운드 어댑터에서 트랜잭션을 연다</li>
 *   <li>필드 주입을 쓴다</li>
 * </ul>
 */
@RestController
@Transactional
public class MisnamedEndpoint {

    @Autowired
    private Object collaborator;

    @Transactional
    public void handle() {
        collaborator.toString();
    }
}
