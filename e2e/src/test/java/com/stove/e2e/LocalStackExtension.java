package com.stove.e2e;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * 테스트 실행 한 번에 {@link LocalStack} 하나를 띄우고, 실행이 끝나면 내린다.
 *
 * <p>{@code @ExtendWith(LocalStackExtension.class)}를 단 클래스의 {@code @BeforeAll}보다 먼저 스택이 뜬다. 스택을
 * 직접 멈추거나 되살려야 하는 테스트는 {@link LocalStack} 매개변수를 받는다.
 */
final class LocalStackExtension implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(LocalStackExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        stack(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == LocalStack.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return stack(context);
    }

    private static LocalStack stack(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE)
                .getOrComputeIfAbsent(Running.class, key -> new Running(LocalStack.start()), Running.class)
                .stack();
    }

    /** 루트 저장소가 닫힐 때(테스트 실행의 끝) 스택을 내린다. */
    private record Running(LocalStack stack) implements ExtensionContext.Store.CloseableResource {

        @Override
        public void close() {
            stack.close();
        }
    }
}
