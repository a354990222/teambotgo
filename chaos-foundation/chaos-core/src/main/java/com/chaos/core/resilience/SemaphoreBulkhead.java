package com.chaos.core.resilience;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无锁 CAS 并发舱壁，用于限制单个下游或路由的在途请求上限。
 *
 * <p>不阻塞等待（快速失败）且不使用 {@code synchronized}，适合 WebFlux 事件循环与 JDK 21 虚拟线程。</p>
 */
public class SemaphoreBulkhead {

    private final String resourceName;
    private final int maxConcurrentCalls;
    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * 创建并发舱壁。
     *
     * @param resourceName 资源名称
     * @param maxConcurrentCalls 允许的最大并发调用数，必须为正数
     */
    public SemaphoreBulkhead(String resourceName, int maxConcurrentCalls) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new IllegalArgumentException("resourceName must not be blank");
        }
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("maxConcurrentCalls must be >= 1");
        }
        this.resourceName = resourceName;
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    /**
     * 非阻塞尝试占用一个并发配额。
     */
    public boolean tryAcquire() {
        while (true) {
            int current = inFlight.get();
            if (current >= maxConcurrentCalls) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 释放一个并发配额。
     */
    public void release() {
        inFlight.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /**
     * 返回当前在途并发请求数。
     */
    public int getActiveCount() {
        return inFlight.get();
    }

    /**
     * 返回最大允许并发数。
     */
    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    /**
     * 返回受保护资源名称。
     */
    public String getResourceName() {
        return resourceName;
    }
}
