package com.stove.common.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 필수 식별자 주입. 인증 정보가 없거나 다른 principal이면 컨트롤러 실행 전에 거부한다. */
public class CurrentIdentityArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentMemberId.class)
                || parameter.hasParameterAnnotation(CurrentSubject.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AccessDeniedException("validated JWT principal is required");
        }
        if (parameter.hasParameterAnnotation(CurrentMemberId.class)) {
            if (parameter.getParameterType() != Long.class && parameter.getParameterType() != long.class) {
                throw new IllegalArgumentException("@CurrentMemberId requires Long or long");
            }
            return CurrentIdentity.memberId(jwt);
        }
        if (parameter.getParameterType() != String.class) {
            throw new IllegalArgumentException("@CurrentSubject requires String");
        }
        return CurrentIdentity.subject(jwt);
    }
}
