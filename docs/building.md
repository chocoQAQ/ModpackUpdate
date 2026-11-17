# 多版本构建

`build-targets.json` 是构建目标的唯一清单，构建脚本、Gradle 和 GitHub Actions 都读取它。当前登记了 Fabric 1.20.1、Fabric 1.21.1 和 NeoForge 1.21.1（21.1.266）。这些目标仍为预览版；自动化构建和模拟加载上下文测试不能代替真实 Minecraft 与启动器兼容性测试。

## 环境

- Windows 使用 PowerShell 5.1+，Linux / macOS 使用 PowerShell 7（`pwsh`）。
- 仓库自带 Gradle 8.14.3 Wrapper，不需要全局安装 Gradle。
- 建议使用 JDK 21 运行 Gradle。完整构建需要同时安装 JDK 17 和 21：共享核心使用 17，目标适配层及测试使用清单中指定的 JDK。
- Fabric 1.20.1 单独构建只需要 JDK 17；其他当前目标需要 JDK 17 和 21。首次构建需要联网下载 Gradle 和依赖。
- 项目没有配置 JDK 自动下载。若工具链未被自动发现，在个人的 `~/.gradle/gradle.properties` 中设置 `org.gradle.java.installations.paths`，用逗号分隔 JDK 根目录；不要将个人绝对路径提交到仓库。也可以用下方的 Gradle 参数临时指定。

## 使用脚本

```powershell
./build.ps1 -ListTargets
./build.ps1 -Platform fabric -Minecraft 1.20.1
./build.ps1 -Platform fabric -Minecraft 1.21.1
./build.ps1 -Platform neoforge -Minecraft 1.21.1
./build.ps1 -Platform fabric -AllVersions
./build.ps1 -Platform all
```

省略所有参数时询问平台。保留 `./build.ps1 fabric`、`neoforge`、`all` 的简写；省略 `-Minecraft` 会构建该平台全部已登记版本，`-AllVersions` 是这一行为的显式写法。`-Minecraft` 不能与 `-AllVersions` 或 `-Platform all` 同时使用。未登记的组合会在启动 Gradle 前报错，退出码为 2。Gradle 构建失败时保留其退出码。

额外参数传给 Gradle，例如：

```powershell
./build.ps1 all --offline --console=plain
./build.ps1 all '-Dorg.gradle.java.installations.paths=E:/Java/jdk17-temurin,E:/Java/jdk21-temurin'
```

也可以直接使用 Gradle（Windows 将 `./gradlew` 换成 `./gradlew.bat`）：

```sh
./gradlew listTargets
./gradlew fabric-1.21.1Dist
./gradlew fabricDist
./gradlew neoforgeDist
./gradlew allDist
```

`build`、`dist` 均汇总到 `allDist`。每个分发任务都运行对应适配层和共享更新器的测试，不仅打包 JAR。原来的 `:fabric:*` / `:neoforge:*` 子项目任务改为 `:fabric-1.20.1:*`、`:fabric-1.21.1:*`、`:neoforge-1.21.1:*`。

## 产物

| 构建选择 | 输出目录 |
| --- | --- |
| 指定平台与 Minecraft | `build/dist/<平台>-<Minecraft>/` |
| 某个平台全部版本 | `build/dist/<平台>/` |
| 所有目标 | `build/dist/all/` |

例如：

```text
build/dist/all/
  ModpackUpdate-0.3.0-preview.1-fabric-mc1.20.1.jar
  ModpackUpdate-0.3.0-preview.1-fabric-mc1.21.1.jar
  ModpackUpdate-0.3.0-preview.1-neoforge-mc1.21.1.jar
  ModpackUpdate-updater-0.3.0-preview.1.jar
```

玩家仅将对应实例的一个模组 JAR 放入 `mods/`。独立 updater 已嵌入每个模组，单独输出的 updater 用于整合包作者执行 `seed`。版本号统一读取 `gradle.properties` 的 `modVersion`，同时写入文件名、模组元数据和 updater manifest。

各目标的中间产物和测试报告位于 `build/targets/<目标 ID>/`；共享更新器报告位于 `updater/build/reports/tests/test/`。分发使用 Gradle `Sync`，每次成功构建会同步所选输出目录，清除该目录内过期产物。仅运行 JAR 任务不会刷新分发目录，应以分发任务成功后的输出为准。

## 添加目标

同一加载器 API 兼容时，在 `build-targets.json` 的 `targets` 中新增记录：

| 字段 | 说明 |
| --- | --- |
| `id` | 必须为 `<platform>-<minecraft>`，不可重复 |
| `platform` | 当前支持 `fabric` 或 `neoforge` |
| `minecraft` | 单个游戏版本，写入元数据的精确兼容声明 |
| `loaderVersion` | 用于编译或测试的加载器版本 |
| `loaderRange` | 模组允许运行的加载器版本范围，按该加载器语法填写 |
| `java` | 目标编译及测试 JDK，同时声明最低运行 Java 版本 |
| `adapter` | 当前与 `platform` 一致，指定共享源码和构建配置目录 |
| `dependencies` | NeoForge 的 FML、语言加载器范围、Mixin 版本及最低 Mixin 版本 |

Fabric 编译直接使用 `loaderVersion`。NeoForge 的当前适配层只依赖 FML / Mixin，不调用游戏类；必须从目标 NeoForge 的官方依赖元数据选取匹配的 FML / Mixin 版本，`loaderVersion` 用于模拟运行环境。`dependencies.languageLoaderRange` 对应 `neoforge.mods.toml` 的语言加载器范围，不能填成 NeoForge 的版本范围。

`settings.gradle` 从清单创建独立 Gradle 子项目，共用 `fabric/` 或 `neoforge/` 源码，每个目标拥有独立的编译、资源处理、测试和打包目录。共享的 `bootstrap/` 与 `updater/` 仅维护一份。新增已适配组合不需要复制整个项目，也不需要修改脚本或 CI 列表。

增加目标后先单独构建，再在相应的真实客户端中验证：离线 / 无更新继续启动、跳过更新、确认更新后退出、文件冲突选择、旧模组禁用、备份和自身替换。仅修改版本声明无法保证运行兼容。

若加载器 API 有变化，需要调整或拆分适配层，并更新清单校验。添加 Forge / Quilt 还需要实现启动入口、模组元数据读取和 Modrinth 依赖校验；它们目前不在支持清单中。Java 17 以下的实例也需要额外适配。

## GitHub Actions

工作流在向 `dev` / `main` 推送、提交 PR 或手动触发时运行，从清单生成矩阵，为每个目标安装 JDK 17 和目标 JDK，执行构建并上传对应 JAR 与测试报告。Linux 测试在 Xvfb 显示环境中运行，允许执行 Swing 测试。

CI 产物用于下载验证，不会自动创建或替换 GitHub Release。新增目标完成真实游戏验证后，再按预览版发布流程上传产物。

多版本构建不会改变更新协议：客户端仍要求目标整合包与当前实例的 Minecraft、加载器类型及加载器版本一致，不自动迁移游戏版本或更换加载器。
