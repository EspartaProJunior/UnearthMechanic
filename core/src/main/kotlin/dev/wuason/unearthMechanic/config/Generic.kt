package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData

open class Generic(private val id: String, private val tools: Set<ITool>,
                   private val baseStage: IStage,
                   private val stages: List<IStage> = mutableListOf(),
                   private val notProtected: Boolean,
                   private val interactionMode: InteractionMode = InteractionMode.INTERACT): IGeneric {

    private val stagesItemsId: HashMap<AdapterData, IStage> = HashMap()

    init {
        for (value in stages) {
            value.getAdapterData()?.let { stagesItemsId[it] = value }
        }
    }

    override fun getId(): String {
        return id
    }

    override fun getTools(): Set<ITool> {
        return tools
    }

    override fun getBaseStage(): IStage {
        return baseStage;
    }

    override fun getStages(): List<IStage> {
        return stages
    }

    override fun getStagesAdapterData(): HashMap<AdapterData, IStage> {
        return stagesItemsId
    }

    override fun getTool(tool: AdapterData): ITool? {
        return tools.find { it.getAdapterData() == tool }
    }

    override fun existsTool(tool: AdapterData): Boolean {
        return tools.any { it.getAdapterData() == tool }
    }

    override fun isLastStage(stage: IStage): Boolean {
        return stage.getStage() == stages.size - 1
    }

    override fun isNotProtect(): Boolean {
        return notProtected
    }

    override fun getBackStage(currentStage: IStage): IStage {
        if (currentStage.getStage() <= 0) return baseStage
        return stages[currentStage.getStage() - 1]
    }

    override fun getInteractionMode(): InteractionMode {
        return interactionMode
    }
}