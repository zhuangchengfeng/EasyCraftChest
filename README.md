# Storage-and-One-Click-Synthesis_repair

「存储和一键合成」模组(NeoForge 1.21.1)的修复/改造工程。

## 本仓库内容

基于原模组的反编译源码(官方映射名,未混淆)进行的两项修复:

1. **替代木材合成 bug 修复**
   - 问题:配方材料为标签(`#minecraft:planks` / `#minecraft:logs`)时,原版只挑一个代表物品
     (通常橡木),整条合成链锁死,即使有其他符合的木材(如云杉原木)也无法合成。
   - 修复:`RecipeResolver.java` 改为对标签候选物品做回溯,仓库无橡木时会自动走
     **云杉原木 → 云杉木板 → 木棍 → 梯子** 这类替代路径。

2. **GUI 重构(双栏布局 + 共享拼音搜索)**
   - 左侧:仓库物品 9×6(54 格/页),分页,左下保留玩家背包。
   - 右侧:合成目录,列出全部物品(12×6,72 格/页),**左键单击直接查看配方**。
   - 顶部共享搜索框,支持**中文拼音**,同时过滤左侧仓库与右侧目录。
   - 配套改动:服务端改为下发全量仓库物品种类,由客户端统一过滤/分页(否则拼音无法在左侧生效)。

## 目录

- `modbuild/` — 可构建的 NeoForge 1.21.1 工程(源码 + 构建配置 + Gradle wrapper)
  - `modbuild/src/main/java` — 修复后的源码(21+ 个类)
  - `modbuild/src/main/resources` — 模组资产(toml/mixins/lang/模型/贴图/配方)
- 构建产物 jar 见 `modbuild/build/libs/`(版本 1.0.2)

## 构建

```bat
cd modbuild
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.11
gradlew.bat build
```

产物:`modbuild\build\libs\storageandoneclicksynthesis-1.0.2.jar`
要求:NeoForge ≥ 21.1.208, Minecraft 1.21.1, Java 21。

> 注:模组原许可证为 "All Rights Reserved"。本仓库为个人修复用途,
> 使用/分发请遵守原作者许可。
