# ModpackUpdate

**预览版 · 0.3.0-preview.1（开发中）**

ModpackUpdate 是一个 Minecraft 客户端整合包更新模组，支持 Modrinth `.mrpack` 格式。游戏启动时检查新版，玩家确认后退出游戏，由独立更新程序完成安装。离线、没有更新或选择跳过时继续启动游戏。

支持静默更新、文件冲突勾选、旧模组禁用和模组自身更新。默认保护键位、视频设置、存档和截图，并备份被覆盖的文件。配置、缓存和备份统一保存在游戏根目录的 `ModpackUpdate/` 下。

| 平台 | Minecraft | 加载器 | Java |
| --- | --- | --- | --- |
| Fabric | 1.20.1 | Fabric Loader 0.16.10+ | 17+ |
| Fabric | 1.21.1 | Fabric Loader 0.16.10+ | 21+ |
| NeoForge | 1.21.1 | NeoForge 21.1.266 | 21+ |

下载对应平台的 JAR，放入实例的 `mods/` 文件夹。整合包作者在 `ModpackUpdate/config/config.json` 中填写更新地址、包标识并启用更新，首次生成的配置默认关闭。更新完成后，从启动器重新启动游戏。 [下载预览版](https://github.com/chocoQAQ/ModpackUpdate/releases)

当前版本已通过自动化测试，真实 Minecraft 整合包启动与不同启动器的兼容性仍待验证。

- [配置文件说明](docs/configuration.md)
- [更新发布协议](docs/publishing-protocol.md)
- [整合包打包流程](docs/packaging.md)
- [多版本构建说明](docs/building.md)

开发使用 `dev` 分支。运行 `./build.ps1 -ListTargets` 查看目标，`./build.ps1 -Platform fabric -Minecraft 1.21.1` 构建指定版本，`./build.ps1 all` 构建全部目标。完整构建需要 JDK 17 和 21，Gradle 自动选择编译与测试工具链。新版开发构建与已发布的预览版可能不同，以 Release 附件为准。
