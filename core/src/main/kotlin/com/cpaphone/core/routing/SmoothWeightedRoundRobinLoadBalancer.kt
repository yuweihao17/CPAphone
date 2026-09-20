package com.cpaphone.core.routing

import com.cpaphone.core.model.AuthCredential
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Nginx 级平滑加权轮询调度器 (Smooth Weighted Round-Robin)
 * 确保高权重凭据均匀分发，杜绝短时间内密集打满单号，达到真正的工业级平滑性。
 */
class SmoothWeightedRoundRobinLoadBalancer : LoadBalancer {
    private val currentWeights = ConcurrentHashMap<String, Int>()
    private val lock = ReentrantLock()

    override fun select(candidates: List<AuthCredential>): AuthCredential? {
        val available = candidates.filter { it.isAvailable }
        if (available.isEmpty()) return null
        if (available.size == 1) return available[0]

        lock.withLock {
            var totalWeight = 0
            var bestCandidate: AuthCredential? = null
            var maxCurrentWeight = Int.MIN_VALUE

            for (candidate in available) {
                val weight = candidate.weight.coerceAtLeast(1)
                totalWeight += weight

                val curWeight = (currentWeights[candidate.id] ?: 0) + weight
                currentWeights[candidate.id] = curWeight

                if (curWeight > maxCurrentWeight) {
                    maxCurrentWeight = curWeight
                    bestCandidate = candidate
                }
            }

            if (bestCandidate != null) {
                val updatedWeight = (currentWeights[bestCandidate.id] ?: 0) - totalWeight
                currentWeights[bestCandidate.id] = updatedWeight
            }

            return bestCandidate
        }
    }

    override fun reset() {
        lock.withLock {
            currentWeights.clear()
        }
    }
}
