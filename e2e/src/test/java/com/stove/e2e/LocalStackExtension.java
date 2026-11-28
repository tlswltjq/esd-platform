package com.stove.e2e;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * 테스트 실행 한 번에 {@link LocalStack} 하나를 띄우고, 실행이 끝나면 내린다.
 *
 * <p>{@code @ExtendWith(LocalStackExtension.class)}를 단 클래스의 {@code @BeforeAll}보다 먼저 스택이 뜬다. 스택을
 * 직접 멈추거나 되살려야 하는 테스트는 {@link LocalStack} 매개변수를 받는다. 앱 일부만 필요하면
 * {@link LocalStackApps}로 고른다.
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
        Set<StackApp> wanted = wanted(context);
        LocalStack stack = context.getRoot().getStore(NAMESPACE)
                .getOrComputeIfAbsent(Running.class, key -> new Running(LocalStack.start(wanted)), Running.class)
                .stack();
        if (!stack.apps().equals(wanted)) {
            // 실행 한 번에 스택은 하나다. 앱 구성이 다른 여정은 다른 Gradle 태스크로 나눈다.
            throw new IllegalStateException(context.getRequiredTestClass().getSimpleName() + " 은 " + wanted
                    + " 을 원하지만 이 실행의 스택은 " + stack.apps() + " 로 떠 있다");
        }
        return stack;
    }

    private static Set<StackApp> wanted(ExtensionContext context) {
        LocalStackApps chosen = context.getRequiredTestClass().getAnnotation(LocalStackApps.class);
        return chosen == null ? EnumSet.allOf(StackApp.class) : EnumSet.copyOf(Arrays.asList(chosen.value()));
    }

    /** 루트 저장소가 닫힐 때(테스트 실행의 끝) 스택을 내린다. */
    private record Running(LocalStack stack) implements ExtensionContext.Store.CloseableResource {

        @Override
        public void close() {
            stack.close();
        }
    }
}
