package com.stove.common.test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 두 요청의 엇갈림을 정해진 순서로 재현하는 테스트 전용 멈춤 지점이다(결정 103).
 *
 * <p>{@link #pausing(PausePoints, Class[])} 로 만든 후처리기를 테스트 설정에 등록하면, 지정한 타입의 빈이
 * 프록시로 감싸져 메서드가 끝날 때마다 {@code "단순타입명.메서드명"} 지점을 지난다. 테스트는
 * {@link #arm(String, String)} 으로 특정 스레드의 다음 통과를 세우고, 다른 요청을 끝낸 뒤 {@link Gate#release()} 로 푼다.
 * 운영 코드에는 아무것도 넣지 않는다.
 */
public final class PausePoints {
    private static final Duration WAIT = Duration.ofSeconds(30);
    private final Map<String, Gate> armed = new ConcurrentHashMap<>();
    private final List<Gate> issued = new CopyOnWriteArrayList<>();
    private final Map<String, Watch> watched = new ConcurrentHashMap<>();

    /** 이름이 {@code threadName}인 스레드가 다음에 {@code point}를 지날 때 세운다. 한 번만 세운다. */
    public Gate arm(String point, String threadName) {
        Gate gate = new Gate(threadName);
        if (armed.putIfAbsent(point, gate) != null) throw new IllegalStateException("이미 세운 지점입니다: " + point);
        issued.add(gate);
        return gate;
    }

    /**
     * 이름이 {@code threadName}인 스레드가 {@code point} 메서드에 들어가고 나오는지 지켜본다.
     * 상대가 잠금 호출 안에서 기다리는지 볼 때 쓴다(시작이 늦은 것과 잠금에 막힌 것을 가른다).
     */
    public Watch watch(String point, String threadName) {
        Watch watch = new Watch(threadName);
        if (watched.putIfAbsent(point + "@" + threadName, watch) != null)
            throw new IllegalStateException("이미 지켜보는 지점입니다: " + point + "@" + threadName);
        return watch;
    }

    /**
     * 테스트마다 남은 지점을 지우고, 세운 스레드를 모두 풀어 준다. 테스트가 중간에 실패해도 잠금을 쥔 채
     * 남지 않게 한다. 돌려준 목록은 쓰이지 않은 지점이며, 남아 있으면 그 테스트의 순서가 틀렸다는 뜻이다.
     */
    public List<String> reset() {
        List<String> left = List.copyOf(armed.keySet());
        armed.clear();
        watched.clear();
        for (Gate gate : issued) gate.release();
        issued.clear();
        return left;
    }

    void entered(String point) {
        Watch watch = watched.get(point + "@" + Thread.currentThread().getName());
        if (watch != null) watch.entered.countDown();
    }

    void returned(String point) {
        Watch watch = watched.get(point + "@" + Thread.currentThread().getName());
        if (watch != null) watch.returned.countDown();
    }

    void passed(String point) {
        Gate gate = armed.get(point);
        if (gate == null || !gate.threadName.equals(Thread.currentThread().getName())) return;
        if (armed.remove(point, gate)) gate.hold();
    }

    /** {@code types}에 해당하는 빈을 감싸 메서드가 끝날 때마다 멈춤 지점을 지나게 한다. */
    public static BeanPostProcessor pausing(PausePoints points, Class<?>... types) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                for (Class<?> type : types) {
                    if (type.isInstance(bean)) return wrap(points, bean, type);
                }
                return bean;
            }
        };
    }

    private static Object wrap(PausePoints points, Object bean, Class<?> type) {
        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(bean);
        factory.addInterface(type);
        MethodInterceptor interceptor = invocation -> {
            String point = type.getSimpleName() + "." + invocation.getMethod().getName();
            points.entered(point);
            Object result;
            try {
                result = invocation.proceed();
            } finally {
                points.returned(point);
            }
            points.passed(point);
            return result;
        };
        factory.addAdvice(interceptor);
        return factory.getProxy(type.getClassLoader());
    }

    /** 이름 붙은 스레드에서 작업을 시작한다. {@link #arm(String, String)}의 스레드 이름과 맞춘다. */
    public static <T> Run<T> start(String threadName, Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(work);
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
        return new Run<>(task);
    }

    /** 세운 지점. 도착을 기다리고 푼다. */
    public static final class Gate {
        private final String threadName;
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private Gate(String threadName) {
            this.threadName = threadName;
        }

        /** 세운 스레드가 지점에 도착할 때까지 기다린다. */
        public void awaitArrival() throws InterruptedException {
            if (!arrived.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                throw new AssertionError(threadName + " 스레드가 멈춤 지점에 오지 않았습니다.");
        }

        public void release() {
            released.countDown();
        }

        private void hold() {
            arrived.countDown();
            try {
                if (!released.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                    throw new IllegalStateException(threadName + " 스레드를 풀어 주지 않았습니다.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    /** 지켜보는 지점. 들어갔는지, 들어간 뒤 일정 시간 안에 나왔는지 본다. */
    public static final class Watch {
        private final String threadName;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch returned = new CountDownLatch(1);

        private Watch(String threadName) {
            this.threadName = threadName;
        }

        /** 지켜보는 스레드가 메서드에 들어갈 때까지 기다린다. */
        public void awaitEntered() throws InterruptedException {
            if (!entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                throw new AssertionError(threadName + " 스레드가 지켜보는 지점에 들어오지 않았습니다.");
        }

        /** 들어간 뒤 {@code within} 동안 메서드에서 나오지 못하면 true. 잠금에 막혀 기다린다는 뜻이다. */
        public boolean stillInsideAfter(Duration within) throws InterruptedException {
            awaitEntered();
            return !returned.await(within.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** 시작한 작업. 결과나 던진 예외를 돌려준다. */
    public static final class Run<T> {
        private final FutureTask<T> task;

        private Run(FutureTask<T> task) {
            this.task = task;
        }

        /** 끝날 때까지 기다려 결과를 돌려준다. 작업이 던진 예외는 그대로 다시 던진다. */
        public T join() throws Exception {
            try {
                return task.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof Exception cause) throw cause;
                throw failed;
            }
        }

        /** 작업이 던진 예외를 돌려준다. 성공했으면 {@link AssertionError}다. */
        public Throwable failure() throws InterruptedException, TimeoutException {
            try {
                T result = task.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                throw new AssertionError("실패해야 할 작업이 성공했습니다: " + result);
            } catch (ExecutionException failed) {
                return failed.getCause();
            }
        }

        /** {@code within} 동안 끝나지 않으면 true. 잠금에 막혀 기다리는지 볼 때 쓴다. */
        public boolean stillRunningAfter(Duration within) throws InterruptedException {
            try {
                task.get(within.toMillis(), TimeUnit.MILLISECONDS);
                return false;
            } catch (TimeoutException waiting) {
                return true;
            } catch (ExecutionException failed) {
                return false;
            }
        }
    }
}
