# Unearth Mechanic

# -> JavaDoc below <-

[here](https://repo.techmc.es/javadoc/releases/dev/wuason/unearth-mechanic/0.1.12e "Go to javadoc")

## Use api

### MAVEN (**.pom**)

Add the repository to your pom.xml file:
```xml
<repositories>
    <repository>
        <id>techmc-studios-releases</id>
        <name>TechMC Repository</name>
        <url>https://repo.techmc.es/releases</url>
    </repository>
</repositories>
```

Add the dependency:
```xml
<dependency>
    <groupId>dev.wuason</groupId>
    <artifactId>unearth-mechanic</artifactId>
    <version>RELEASE-VERSION</version>
    <scope>provided</scope>
</dependency>
```

### GRADLE (**build.gradle**)

Add the repository to your build.gradle file:
```gradle
repositories {
    maven { url 'https://repo.techmc.es/releases' }
}
```

Add the dependency:
```gradle

dependencies {
    compileOnly 'dev.wuason:unearth-mechanic:RELEASE-VERSION'
}
```

### GRADLE KOTLIN DSL (**build.gradle.kts**)

Add the repository to your build.gradle.kts file:
```kotlin
repositories {
    maven("https://repo.techmc.es/releases")
}
```

Add the dependency:
```kotlin
dependencies {
    compileOnly("dev.wuason:unearth-mechanic:RELEASE-VERSION")
}
```

### Unearth WIKI for user [Link](https://plugins.elitefantasy.net/mechanics/unearthmechanic)
### Mechanics WIKI for user [Link](https://wiki.techmc.es/en/mechanics)
