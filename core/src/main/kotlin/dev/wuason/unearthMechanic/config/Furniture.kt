package dev.wuason.unearthMechanic.config

class Furniture(id: String, tools: Set<ITool>, baseStage: FurnitureStage, stages: List<IStage>, notProtected: Boolean, interactionMode: InteractionMode):
    Generic(id, tools, baseStage, stages, notProtected,interactionMode), IFurniture