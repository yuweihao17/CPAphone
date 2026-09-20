package com.cpaphone.core.routing

import com.cpaphone.core.model.AuthCredential

/**
 * 深度优先（优先打满单号）调度器 (Fill-First)
 * 优先使用排在最前面的可用凭据，降低多号同时暴露风险，直至该号遭遇冷却再轮转下一号
 */
class FillFirstLoadBalancer : LoadBalancer {
    override fun select(candidates: List<AuthCredential>): AuthCredential? {
        return candidates.firstOrNull { it.isAvailable }
    }

    override fun reset() {
        // 无状态调度器，无需重置
    }
}
