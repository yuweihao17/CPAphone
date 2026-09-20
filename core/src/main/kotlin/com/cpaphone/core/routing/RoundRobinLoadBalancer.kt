package com.cpaphone.core.routing

import com.cpaphone.core.model.AuthCredential
import java.util.concurrent.atomic.AtomicInteger

/**
 * 经典原子轮询调度器 (Round-Robin)
 * 采用无锁原子计数器，完全避免并发锁竞争与阻塞
 */
class RoundRobinLoadBalancer : LoadBalancer {
    private val counter = AtomicInteger(0)

    override fun select(candidates: List<AuthCredential>): AuthCredential? {
        val available = candidates.filter { it.isAvailable }
        if (available.isEmpty()) return null
        if (available.size == 1) return available[0]

        val index = (counter.getAndIncrement() and Int.MAX_VALUE) % available.size
        return available[index]
    }

    override fun reset() {
        counter.set(0)
    }
}
