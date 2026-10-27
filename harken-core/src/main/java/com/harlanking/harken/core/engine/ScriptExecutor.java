package com.harlanking.harken.core.engine;

import com.harlanking.harken.core.HarkenException;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 超时执行器：一次执行一个新线程，不复用线程池。
 *
 * <p><b>为什么不用线程池：</b> Groovy 脚本可以调任意业务代码。一旦卡在不可中断的
 * 阻塞调用里（{@code synchronized} 死锁、socket read、native 方法），这个线程就永远
 * 污染了。放回池子会被下一个任务复用，故障会累积。所以宁可丢弃（ADR-08）。
 *
 * <p><b>成本要有数：</b> 每次执行一个线程，创建开销约 50~100μs。对人手点执行无所谓，
 * 但 AI 高频调用时要把并发卡住，否则线程数失控。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class ScriptExecutor {

    private final AtomicInteger sequence = new AtomicInteger();

    /**
     * 在独立线程中执行任务，超过时限即中断并抛出超时。
     *
     * <p>任务抛出的异常会原样透传（{@code RuntimeException} / {@code Error}），
     * checked exception 会被包装成 {@link HarkenException}。
     *
     * @param timeoutMs 超时毫秒，非正数按立即超时处理
     * @param task      要执行的任务，不能为 {@code null}
     * @param <T>       返回值类型
     * @return 任务返回值
     * @throws HarkenException.Timeout      超时未返回
     * @throws HarkenException              等待过程中被中断，或任务抛出 checked exception
     */
    public <T> T call(long timeoutMs, Callable<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "harken-exec-" + sequence.incrementAndGet());
        worker.setDaemon(true);
        // 脚本里 import 业务类、加载资源都靠线程上下文 CL
        worker.setContextClassLoader(contextClassLoader());
        worker.start();

        try {
            worker.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            throw new HarkenException("等待脚本执行时被中断", e);
        }

        if (worker.isAlive()) {
            // 只是"尽力而为"：能响应中断的会退出，卡在阻塞调用的会变成僵尸线程
            worker.interrupt();
            throw new HarkenException.Timeout(timeoutMs);
        }

        Throwable t = failure.get();
        if (t != null) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new HarkenException("脚本执行失败: " + t.getMessage(), t);
        }
        return result.get();
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : ScriptExecutor.class.getClassLoader();
    }
}
