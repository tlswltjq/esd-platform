package com.stove.studio.core.domain.access;

/**
 * 인증 정보 발급 직후 저장 정보와 토큰 원문을 함께 전달하는 결과.
 *
 * @param credential 발급해 저장한 프로젝트 인증 정보.
 * @param token 발급 결과로 전달하는 인증 토큰 원문.
 */
public record IssuedProjectCredential(ProjectCredential credential, String token) {
}
