package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData

data class TimedSequenceInteraction(
    val collectWindowTicks: Long,
    val outcomes: Map<String, TimedSequenceOutcome>
) {
    fun findOutcome(toolAdapterData: AdapterData?): TimedSequenceOutcome? {
        if (toolAdapterData != null) {
            outcomes.values.firstOrNull { it.matches(toolAdapterData) }?.let {
                return it
            }
        }

        return outcomes.values.firstOrNull { it.isFallback() }
    }
}

data class TimedSequenceOutcome(
    val id: String,
    val tools: Set<ITool>,
    val successStage: Stage
) {
    fun isFallback(): Boolean {
        return tools.isEmpty()
    }

    fun matches(toolAdapterData: AdapterData): Boolean {
        if (tools.isEmpty()) return false

        return tools.any { tool ->
            tool.getAdapterData() == toolAdapterData
        }
    }

    fun getMatchingTool(toolAdapterData: AdapterData?): ITool? {
        if (toolAdapterData == null) return null

        return tools.firstOrNull { tool ->
            tool.getAdapterData() == toolAdapterData
        }
    }
}