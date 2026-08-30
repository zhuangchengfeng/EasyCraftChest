# 存储和一键合成 - 修复版构建工程

基于 NeoForge 1.21.1 (21.1.249) 重新构建的修复版,版本号 **1.0.2**。

## 修复内容

修复了 `RecipeResolver.java` 的替代原料 bug:
当配方材料是标签(如 `#minecraft:planks` / `#minecraft:logs`)时,原版只会挑一个代表物品
(优先挑仓库已有的,否则挑标签第一个,通常是橡木),整条合成链锁死在该物品上。
修复后会对标签内的候选物品做回溯:例如仓库有云杉原木但没有橡木时,
合成梯子会自动走 **云杉原木 → 云杉木板 → 木棍 → 梯子** 的路径。

改动文件:`src/main/java/com/stroeud/server/recipe/RecipeResolver.java`

## 重新编译

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.11
gradlew.bat build
```

产物在 `build/libs/storageandoneclicksynthesis-1.0.2.jar`。
首次构建会从腾讯镜像下载 Gradle 与 NeoForge 依赖,后续构建很快。

注意:gradle wrapper 的 distributionUrl 指向腾讯镜像(`mirrors.cloud.tencent.com/gradle`),
如需官方源可改 `gradle/wrapper/gradle-wrapper.properties` 中的 URL。

## 目录

- `src/main/java` — 反编译并修复后的源码(21 个类)
- `src/main/resources` — 原 jar 的资产(toml/mixins/lang/模型/贴图/配方)
- `build/libs/*.jar` — 构建产物
