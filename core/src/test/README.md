# Unit test API boundary

CustomStack and CustomFurniture are signature-only fixtures for explicitly mocked ItemsAdder API calls. Their methods fail if invoked without Mockito setup. This avoids loading ItemsAdder's runtime-downloaded FastNBT dependency in unit tests.

Main production compilation uses the external, genuine ItemsAdder JAR. These fixtures exist only in src/test and are never included in the plugin. Hitbox tests still execute genuine IA 4.0.18 js.x/q/z geometry code from that JAR, with its metadata resolver and NMS adapter mocked. Barrier ADD is a deterministic model; live-server results are separate maintainer evidence.
