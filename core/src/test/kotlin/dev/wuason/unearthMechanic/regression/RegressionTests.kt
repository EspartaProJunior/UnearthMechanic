package dev.wuason.unearthMechanic.regression
import org.junit.jupiter.api.Test
class RegressionTests {
 @Test fun replacement() = dev.wuason.unearthMechanic.regression.replacement.runCases()
 @Test fun placementAndSwing() = dev.wuason.unearthMechanic.regression.placement.runCases()
 @Test fun transitionOwnershipAndSpam() = dev.wuason.unearthMechanic.regression.transitions.runCases()
 @Test fun hitboxRestorationAndFailSafe() = dev.wuason.unearthMechanic.regression.hitbox.V43Tests.runCases()
 @Test fun loadedFurnitureGeometry() = dev.wuason.unearthMechanic.system.compatibilities.ia.LoadedGeometryTests.runCases()
}
