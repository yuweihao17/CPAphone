package com.cpaphone.core.routing

import com.cpaphone.core.model.AuthCredential

/**
 * 负载均衡调度器契约接口
 */
interface LoadBalancer {
    /**
     * 从候选凭据列表中挑选出一个最优凭据
     * @param candidates 属于同一目标提供商或模型的全部凭据
     * @return 选中的凭据；若无可用的凭据则返回 null
     */
    fun select(candidates: List<AuthCredential>): AuthCredential?

    /**
     * 重置调度器内部状态（如加权计数器）
     */
    fun reset()
}
