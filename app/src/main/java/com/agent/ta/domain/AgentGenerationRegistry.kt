package com.agent.ta.domain

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

class AgentGenerationRegistry {
    private val generation = AtomicLong(0)

    fun capture(agentId: Long): AgentOperationContext =
        AgentOperationContext(agentId, generation.get())

    fun advance(): Long = generation.incrementAndGet()

    fun isCurrent(context: AgentOperationContext): Boolean =
        context.generation == generation.get()

    fun isCurrent(context: AgentOperationContext, activeAgentId: Long): Boolean =
        isCurrent(context) && context.agentId == activeAgentId

    fun requireCurrent(context: AgentOperationContext) {
        if (!isCurrent(context)) {
            throw CancellationException("Agent operation context expired")
        }
    }

    companion object {
        val shared = AgentGenerationRegistry()
    }
}
