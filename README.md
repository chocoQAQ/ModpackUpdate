# ModpackUpdate

Fabric 客户端整合包更新模组。初版针对 Minecraft 1.20.1、Java 17+、Fabric Loader 0.16.10+，支持 Modrinth `.mrpack` 格式。发布包声明的 Minecraft 和 Fabric Loader 版本必须与当前实例一致。

在 `preLaunch` 阶段检查更新。离线、检查失败、没有新版或玩家跳过时继续启动。确认更新后，独立 Java 更新程序接管，等待游戏进程完全退出，再下载和安装文件。完成后由玩家从启动器重新启动游戏。

## 开发计划

日常开发使用 `dev` 分支。下一阶段适配 NeoForge + Minecraft 1.21.1，复用独立更新程序、Modrinth 发布协议和文件冲突处理，补充对应的启动入口与版本校验。

## 构建与安装

使用 JDK 17 或 21，将 `JAVA_HOME` 设置为对应 JDK。首次构建需要联网。

```powershell
.\gradlew.bat build
```

```sh
./gradlew build
```

构建结果在 `build/dist/`。把 `ModpackUpdate-0.1.0.jar` 放入实例 `mods/`。独立更新程序已嵌入模组，`ModpackUpdate-updater-0.1.0.jar` 同时单独输出，供作者生成初始安装记录和调试。

项目直接依赖 Fabric Loader 的公开入口 API，使用标准 Java Gradle 工程。入口不引用 Minecraft 类，因此无需映射或 Fabric API。游戏内集成验收步骤见下文。

## 实例目录

```text
<游戏根目录>/
  mods/
    ModpackUpdate.jar
    old-mod.jar.disable
  ModpackUpdate/
    config/
      config.json
      installed.json
    downloads/                  下载缓存、解压暂存，按 SHA-512 命名
    runtime/                    按内容哈希命名的独立程序、日志、进程登记
    1.0.0/<更新编号>/mods/       从 1.0.0 更新时备份的旧模组
    1.0.0/<更新编号>/config/     被覆盖的其他文件备份
    transaction.json            安装期间的恢复记录
    update.lock
```

目录中的版本号表示被替换文件所属的旧版。更新编号允许多次更新和失败重试保留各自备份。日志在 `runtime/check-*.log`。下载缓存会复用，初版保留缓存供重试，游戏与更新程序都关闭后可手动清理 `downloads/`。运行记录和配置集中在 `ModpackUpdate/`。

## 作者配置

完整字段说明、默认值和安装记录解释见[配置文件文档](docs/configuration.md)。

首次启动自动生成 `ModpackUpdate/config/config.json`，默认关闭更新。

```json
{
  "enable": true,
  "update-service-url": "https://updates.example.com/my-pack/latest.json",
  "pack-id": "my-pack",
  "silent-update": false,
  "delete-replaced-mods": false,
  "timeout-seconds": 10,
  "protected-paths": [
    "options.txt",
    "optionsof.txt",
    "servers.dat",
    "saves/",
    "screenshots/"
  ]
}
```

`silent-update` 跳过新版确认，无冲突时直接安装。有冲突或新增可选文件时仍显示选择窗口。`delete-replaced-mods` 默认关闭，也可以在安装确认窗口临时勾选。启用后，安装事务成功提交时删除本次被覆盖的旧 JAR 备份。安装进行期间仍保留可回滚的旧文件。

`protected-paths` 使用相对路径，末尾 `/` 表示目录前缀。这些路径跳过安装，也不进入全选列表。作者可补充模组专用的键位、视频设置文件。`ModpackUpdate/` 自身始终由更新程序管理，发布包中同名文件不会覆盖正在使用的配置或安装记录。

`timeout-seconds` 控制连接、请求及小文件响应超时，限制为 2–120 秒。超过 16 MiB 的文件响应体最多允许下载 30 分钟。元数据与每个产物有大小限制。下载失败可再次启动重试，已完整校验的缓存会复用。

## 文件处理

| 情况 | 行为 |
| --- | --- |
| 新旧目标哈希相同 | 跳过本地内容读取和下载 |
| 本地文件与旧版基线一致 | 自动覆盖，原文件移入版本备份 |
| 本地文件与新版一致 | 采用该文件，跳过下载 |
| 本地修改、主动移除、或现有文件缺少基线 | 进入冲突选择，默认保留 |
| 新增可选文件且本地没有 | 进入选择列表，默认不安装 |
| 旧版受管模组在新版中消失 | 改名为 `.jar.disable` |
| 旧版受管的其他文件在新版中消失 | 保留 |
| 玩家自装、清单外的文件 | 保留 |

冲突窗口提供逐项复选框、全选、全不选和取消更新。仅选中的冲突采用新版操作。新版 JAR 改名时，通常同时包含新增新版、禁用旧版两个操作。安装前检查直接放在 `mods/` 下的 JAR 是否出现重复 Fabric mod ID，发现重复会取消整次安装。完整模组依赖和嵌套 JAR 的解析仍由 Fabric Loader 在下次启动时完成。

旧文件的缺席只能由本地 `installed.json` 和新完整清单对比得出，发布协议不接受任意删除命令。禁用的文件保留在 `mods/`，若 `.disable` 已存在会使用带更新编号的名称。已被玩家修改的旧模组禁用前同样需要勾选。

备份和安装采用事务日志。所有待安装内容下载、校验完成后才开始写入实例。成功提交 `installed.json` 后，安装才算完成。中途失败会恢复旧文件，进程异常退出会在下次启动交给独立程序恢复。恢复失败会终止启动并保留日志与备份。

保留差异会记录在 `installed.json` 的 `retained` 字段。该记录保存旧的原始基线和新的目标哈希，后续新版再次改动这个路径时会重新比较。选择保留可能导致整合包配置或模组组合与作者版本不同，需要玩家自行决定。

## 自身更新

把新版 ModpackUpdate 当作普通受管 JAR 发布即可，建议固定目标路径 `mods/ModpackUpdate.jar`。独立更新程序从 `runtime/` 启动，游戏退出后可以备份并覆盖原模组 JAR。下一次启动会从新版模组释放新版独立程序。

当前更新由旧版内嵌更新程序执行，因此更新服务必须提供旧版能够读取的协议 v1。升级协议应先用兼容协议发布支持新协议的 ModpackUpdate。

## 发布和验收

- [配置文件说明](docs/configuration.md)
- [发布协议](docs/publishing-protocol.md)
- [打包和派生产物流程](docs/packaging.md)

自动测试覆盖文件冲突、用户保护路径、备份与直接删除选项、旧模组禁用、自身 JAR 替换、重复模组 ID、安装中断恢复、路径校验、下载校验与缓存。

独立进程测试会启动模拟游戏，持有旧 JAR，检查交接信号到达后、游戏退出前该文件仍未改变，再验证更新后的文件与版本备份。Swing 测试检查逐项勾选和全选/全不选，并将窗口截图输出到 `updater/build/qa/`。无图形环境时跳过窗口测试。真实 Minecraft 启动与不同启动器的子进程生命周期仍需按下面步骤实测。

真实游戏验收需要一个 Minecraft 1.20.1 Fabric 实例和本地或 HTTPS 更新源。

1. 配置关闭、无新版、断网各启动一次，确认游戏正常进入。
2. 发布新版并拒绝更新，确认继续原版游戏。
3. 接受更新，确认游戏进程退出后才改变文件，更新完成后重新启动。
4. 修改一个配置，确认复选框、全选、全不选和取消更新生效。
5. 发布改名 JAR 和新版 ModpackUpdate，确认旧 JAR 被禁用、自身替换后能再次检查。
6. 测试默认备份和直接删除选项，检查版本目录。
7. 分别在下载和安装阶段终止独立进程，再次启动检查恢复结果。

`preLaunch` 之前已发生 Fabric 扫描和依赖解析。如果当前模组组合在此之前就导致 Loader 报错，更新入口无法运行。初版的更新提示和冲突页使用独立 Swing 窗口，需要带桌面模块的 Java 运行环境。初版不会自动修改启动器的 Minecraft / Loader 配置。
