# main 分支代码审查

审查日期为 2026-11-19，基准提交为 `fb58e0ae638575232931e77f9740fcb8dbb42b07`，版本为 `0.3.0-preview.2`。该提交是审查开始时本地 `main` 与本地 `origin/main` 的共同位置。审查使用独立导出副本，结论不包含工作期间其他任务提交到 `dev` 的改动。

确认 5 个问题：3 个 P1 和 2 个 P2，均有最小复现。现有八个目标构建及 59 项测试全部通过，但没有覆盖下面的路径和状态边界。实现结构见[具体实现说明](implementation-main.md)。

| 编号 | 级别 | 问题 | 复现场景 |
| --- | --- | --- | --- |
| R1 | P1 | Windows 上仅修改文件名大小写会禁用刚安装的新版，却提交成功 | `CASE_RENAME` |
| R2 | P1 | 非规范路径绕过文件身份去重，破坏恢复，也能绕过文件保护 | `PATH_ALIAS`、`PROTECTED_ALIAS` |
| R3 | P1 | helper 异常退出时，即使有未完成事务也放行游戏 | `PENDING_HELPER_FAILURE` |
| R4 | P2 | 内容未变时不更新可选属性，后续必需文件可能被漏装 | `OPTIONAL_TRANSITION` |
| R5 | P2 | 只按缓存文件存在性复用 updater，损坏后不能自动修复 | `CORRUPT_HELPER` |

P1 表示需要优先修复的安装正确性或实例一致性问题；P2 表示在特定状态转换或缓存损坏条件下影响功能的问题。R1–R5 保留被审查 main 提交的故障描述；后续修复状态如下。

## 后续修复状态

2026-11-19，五项修复已加入当前 `dev` 工作区，基于 `ef3bfc5` 的 `0.3.0-preview.3` 实现，同时保留并验证该版本的完整包兜底更新路径。

| 问题 | 修复实现 | 正确行为的回归覆盖 |
| --- | --- | --- |
| R1 | 基线、目标和活动模组集合以平台 `Path` 比较身份；下一份基线采用目标拼写 | 同内容和不同内容的大小写改名均保留一个活动 JAR；跳过冲突时保留玩家修改 |
| R2 | 原始字符串在构造 `Path` 前验证；完整目标集和安装选择验证重复及父子冲突 | 重复 `/`、结尾 `/`、`.`、`..` 被拒绝；路径冲突不写事务；保护文件保留 |
| R3 | bootstrap 在 helper 结束或准备失败后检查事务是否明确不存在 | helper 异常、runtime 目录异常且有事务时阻止启动；无事务的普通检查错误继续游戏 |
| R4 | 基线属性推进不再依赖内容是否变更 | 同哈希的必需→可选→必需会重新检查并可恢复缺失模组；保留作者哈希和玩家选择 |
| R5 | 复用前校验普通文件、大小和 SHA-256；临时文件替换并清理 | 长度不同及长度相同的缓存损坏均重建；有效缓存不重写 |

路径实现集中在 [FilesSupport.java](../updater/src/main/java/dev/modpackupdate/updater/FilesSupport.java)。`relative()` 先检查原始 `/` 路径段，避免 `Path.of()` 折叠别名后丢失验证信息；`validateFilePaths()` 检查完整文件集合；`byPath()` 用平台路径键建立基线与目标索引。在 Windows 上，两个大小写拼写因此不会进入新增与禁用两条操作。区分大小写的平台仍按不同路径规划。

`Planner.next()` 先同步完整目标中的可选属性和路径拼写，再按玩家选择推进内容哈希。未采用的新版仍保留原作者基线与 `retained` 状态。`Installer` 在写日志前再次校验所选目标，并为旧 JAR 的 `.disable` 名称预留位置，避免与同批新增文件冲突。

事务阻断和缓存重建位于 [UpdateBootstrap.java](../bootstrap/src/main/java/dev/modpackupdate/bootstrap/UpdateBootstrap.java)。事务检查使用 `Files.notExists(..., NOFOLLOW_LINKS)`：只有能够明确确认记录不存在时，异常检查才允许继续游戏。缓存替换优先使用原子移动，不支持时退化为替换移动，始终清理临时文件。

新增的 [ReviewRegressionTest](../updater/src/test/java/dev/modpackupdate/updater/ReviewRegressionTest.java) 包含 17 项场景，[UpdateBootstrapTest](../bootstrap/src/test/java/dev/modpackupdate/bootstrap/UpdateBootstrapTest.java) 包含 7 项场景。启动桥接器测试已纳入所有分发任务。完整包兜底测试也增加非规范路径和文件父子冲突输入。

修复后在本机 Windows、JDK 17/21、Gradle 8.14.3 上执行 `allDist --continue --offline` 成功：**8 个目标、90 项测试全部通过，失败 0、错误 0、跳过 0**；其中 updater 57 项、bootstrap 7 项、适配层 26 项。`build/dist/all/` 生成八个模组 JAR 和一个独立 updater JAR，构建日志为 `build/review-fixes-build.log`。

CI 已加入 Windows Fabric 1.20.1 构建任务，执行共享核心、桥接器及适配层测试；原 Linux 八目标矩阵继续保留。此次验证为本地运行结果，新增远程 CI 任务将在推送后执行。真实 Minecraft 与启动器验证仍需单独进行。

## 2026-11-21 后续风险修复

`0.3.0-preview.4` 在上述修复上继续处理当前工作区审查发现的问题：

- 禁用目的地参与完整路径集合验证，包含父子路径冲突；旧版遗留的禁用目录冲突事务也可恢复。
- 跳过新版模组时，保留无法确认已有替代品的旧模组；已采用替代文件的模组 ID 用于确认可执行的旧版禁用。
- 完整包可内嵌 `modpackupdate.release.json`，与接口共用发布编号。缺少编号的包，以及从历史无编号安装切回接口，均要求人工确认，不静默判断版本先后。
- 检查有总时限及可取消窗口，bootstrap 只在检查阶段执行超时保护，确认窗口允许用户等待。
- 缓存容量、备份保留数量可配置；安装前检查空间，未完成事务期间禁止清理。备份默认不自动删除。
- 新增独立 `repair` 命令，可在游戏未启动时恢复事务或重新处理当前版本差异。安装记录区分 `complete` 和 `modified`。
- 事务记录已开始操作数量，安装临时文件替换前刷盘；增加日志写入、操作开始、备份、替换和提交阶段的中断恢复测试。

本机 Windows、JDK 17/21 执行 `./build.ps1 all --offline`：八个目标构建成功，**112 项测试通过，失败 0、错误 0、跳过 0**。其中含独立更新器进程的自身替换、无编号兜底确认、独立修复与活动游戏拒绝、取消窗口、慢速下载超时、历史事务恢复和备份保留测试。图形测试输出位于 `updater/build/qa/`。这些为自动化和模拟故障验证，未替代真实 Minecraft / 启动器兼容性或硬件断电验证。

同日复核 CI 时，Windows 短路径修正已通过，Linux 仍因禁用候选路径沿用不区分大小写的发布路径校验而失败。已将该分支统一到平台操作路径校验，发布清单仍保持严格校验。另外新增 6 个跨版本保留选择和恢复文件缺失场景，修复“后续发布自动禁用此前保留的旧模组”以及“禁用文件和原文件均未恢复仍清除事务”两个遗漏。新增场景在修复前有 5 个失败，修复后本地八目标构建及 **118 项测试全部通过，无失败、无跳过**。

## R1 Windows 大小写改名会把新版 JAR 禁用

**定位：** [Planner.java 第 16–18 行](https://github.com/chocoQAQ/ModpackUpdate/blob/fb58e0ae638575232931e77f9740fcb8dbb42b07/updater/src/main/java/dev/modpackupdate/updater/Planner.java#L16-L18)、[第 32–39 行](https://github.com/chocoQAQ/ModpackUpdate/blob/fb58e0ae638575232931e77f9740fcb8dbb42b07/updater/src/main/java/dev/modpackupdate/updater/Planner.java#L32-L39)。

旧基线为 `mods/Example.jar`，新发布改为 `mods/example.jar` 并更新内容。在 Windows 的不区分大小写文件系统中，这两个路径对应同一个文件；但基线和目标映射用区分大小写的字符串键。

`plan()` 把新版路径判为“缺少基线”的新增冲突项，同时把旧路径判为从清单移除的 JAR，并自动选择禁用。玩家全选后，安装器先在同一物理位置安装新版，再按旧字符串路径把新版改名为 `.disable`，最后成功提交 `release-id`。若按默认选择跳过新增冲突，旧版也会被自动禁用。

**已复现结果：** 全选安装后，`installed.json` 的发布编号为 `2`，活动 JAR 不存在，`.disable` 中反而是新版内容，事务日志已清理。游戏可能缺少必需模组；同一发布不会再次触发更新。

**建议：** 在目标、旧基线、活动模组集合和事务操作之间使用一致的文件身份比较。对大小写改名明确合并成一个替换或受控改名操作；在执行前拒绝多个操作指向同一物理文件。补充 Windows 的仅改大小写、同时改内容，以及默认选择和全选场景。

## R2 路径别名会破坏事务恢复和保护规则

**定位：** [FilesSupport.java 第 24–49 行](https://github.com/chocoQAQ/ModpackUpdate/blob/fb58e0ae638575232931e77f9740fcb8dbb42b07/updater/src/main/java/dev/modpackupdate/updater/FilesSupport.java#L24-L49)、[第 54–60 行](https://github.com/chocoQAQ/ModpackUpdate/blob/fb58e0ae638575232931e77f9740fcb8dbb42b07/updater/src/main/java/dev/modpackupdate/updater/FilesSupport.java#L54-L60)、[Protocol.java 第 114–116 行](https://github.com/chocoQAQ/ModpackUpdate/blob/fb58e0ae638575232931e77f9740fcb8dbb42b07/updater/src/main/java/dev/modpackupdate/updater/Protocol.java#L114-L116)。

`Path.of()` 会把重复分隔符和结尾分隔符折叠为文件系统路径，但 `resolve()` 没有拒绝这些写法。协议去重、保护规则、目标表和事务日志仍使用原始字符串，导致两个字符串可以代表同一目标。

复现一：同一索引同时包含 `config/a.json` 和 `config//a.json`，分别声明不同的新内容。两条路径通过协议验证。对已有旧文件全选安装时，第二次备份指向与第一次相同的位置而失败；自动恢复从第二条操作开始，却看到第一条操作写入的内容，将其认作外部修改。

**已复现结果：** 安装失败，文件保留第一份新内容，`transaction.json` 留存；再次调用 `recover()` 仍失败。单靠重新启动不能恢复到旧状态。

复现二：索引目标 `options.txt/` 被解析为实际的 `options.txt`，但默认保护规则只匹配原字符串 `options.txt`。该目标进入冲突列表，玩家全选后覆盖受保护的键位和视频设置文件。

**已复现结果：** `protectedPath("options.txt/")` 为假，实际 `options.txt` 被替换成作者内容。保护规则本应在进入冲突列表前跳过该文件。

**建议：** 在首次解析时统一验证规范的 `/` 相对文件路径，拒绝空路径段、重复 `/` 和结尾 `/`；保护匹配、去重、基线和事务均使用同一结果。同时校验文件路径之间的父子冲突，安装前确保操作目标唯一。不能仅在实际 `Path` 操作时进行规范化。

## R3 helper 失败时未完成事务仍被放行

**定位：** [UpdateBootstrap.java 第 45–53 行](https://github.com/chocoQAQ/ModpackUpdate/blob/fb58e0ae638575232931e77f9740fcb8dbb42b07/bootstrap/src/main/java/dev/modpackupdate/bootstrap/UpdateBootstrap.java#L45-L53)、[Main.java 第 31–40 行](https://github.com/chocoQAQ/ModpackUpdate/blob/fb58e0ae638575232931e77f9740fcb8dbb42b07/updater/src/main/java/dev/modpackupdate/updater/Main.java#L31-L40)。

bootstrap 仅在 helper 返回 `20` 时阻止启动，其他结束码和普通异常均正常返回。helper 在进入 `checkLocked()` 的恢复判断前，仍可能因锁文件访问、JAR 或启动问题失败，并由外层 `main` 返回 `1`。此时 bootstrap 没有检查本地是否已有 `transaction.json`。

复现构造一份有效的未提交事务：旧文件有备份，目标文件已写入部分新内容；同时将 `update.lock` 设置为目录，模拟无法打开锁文件的错误。helper 在恢复前退出 `1`。

**已复现结果：** `UpdateBootstrap.check()` 正常返回，事务日志还在，文件仍为 `partially-installed`。在实际入口中这意味着游戏获准继续启动，使用未完成安装的混合状态。该复现验证的是失败处理，不要求更新源或网络参与。

**建议：** 普通离线检查可以放行；已知存在未完成事务或无法可靠确认事务状态时，helper 异常、非正常结束码和启动失败应阻止游戏继续启动。明确区分“可继续游戏的检查失败”和“实例需要恢复”，并增加恢复路径外层错误的覆盖。

## R4 相同哈希下可选属性没有推进

**定位：** [Planner.java 第 17–18 行](https://github.com/chocoQAQ/ModpackUpdate/blob/fb58e0ae638575232931e77f9740fcb8dbb42b07/updater/src/main/java/dev/modpackupdate/updater/Planner.java#L17-L18)、[第 43–59 行](https://github.com/chocoQAQ/ModpackUpdate/blob/fb58e0ae638575232931e77f9740fcb8dbb42b07/updater/src/main/java/dev/modpackupdate/updater/Planner.java#L43-L59)。

规划在目标哈希不变时跳过该路径，只有旧记录已经是可选、新目标变成必需时例外。`next()` 又只复制旧文件表并处理变更项，没有利用完整 `targets` 刷新 `optional`。

复现版本顺序：A 中文件必需；B 内容哈希不变但改成可选；玩家在 B 删除该可选文件；C 又以相同哈希改成必需。处理 B 后的基线仍写着 `optional: false`，C 因而不被识别为可选改必需，并在同哈希分支提前跳过。

**已复现结果：** B 的可选属性仍为假；C 的变更列表为空，即使必需 JAR 已缺失。

**建议：** 把发布属性推进与是否需要读盘、下载分开。内容未变也要把最新 `optional` 写入基线；可选改必需时保留现有重新检查逻辑。补充 `required → optional → required` 的同哈希跨版本用例。

## R5 updater 缓存存在即复用，损坏后不会重建

**定位：** [UpdateBootstrap.java 第 62–68 行](https://github.com/chocoQAQ/ModpackUpdate/blob/fb58e0ae638575232931e77f9740fcb8dbb42b07/bootstrap/src/main/java/dev/modpackupdate/bootstrap/UpdateBootstrap.java#L62-L68)。

helper 文件名中的 SHA-256 来自模组内嵌的字节，但命中缓存时只调用 `Files.exists()`，没有检查缓存内容。即使原模组内有完好的 updater，损坏的同名缓存仍被一直交给 Java 启动。

复现先根据真正内嵌 updater 的 SHA-256 建立正确文件名，再把内容写成 `not-a-jar`；连续调用两次实际的 `extractHelper()`。

**已复现结果：** 两次都返回同一损坏文件，内容没有被修复。后续启动不能从原模组恢复更新能力，直到删除缓存或内嵌版本变化。复现通过反射调用释放方法，避免执行损坏 JAR 引发平台启动器交互。

**建议：** 复用前验证普通文件类型、长度和 SHA-256；不匹配时从内嵌资源重新释放，并用临时文件安全替换。与 R3 分别处理：修复缓存不能代替未完成事务的失败阻断。

## 验证与复现

### 原有构建和测试

固定 main 导出副本的 `allDist --continue --offline` 最终执行成功，使用 JDK 17/21 和 Gradle 8.14.3。八个目标均构建成功，共 59 项测试，失败 0、错误 0、跳过 0；其中 updater 33 项，适配层合计 26 项。

初次运行中，沙箱不允许子进程访问 Windows 默认临时路径的短文件名形式，产生 `AccessDeniedException`。将 `JAVA_TOOL_OPTIONS` 的 `java.io.tmpdir` 指向副本内目录后，原失败测试全部通过。这些初始失败没有作为仓库缺陷计入。依赖和 Gradle 使用本地已有缓存；没有以网络下载失败判断代码有误。

测试报告位于审查副本的 `updater/build/reports/tests/test/` 与 `build/targets/<id>/reports/tests/test/`。本机副本位置为 `build/review-main-fb58e0a/`，完整构建日志为其 `build/review-build.log`。

### 额外复现程序

[MainReviewProbe.java](review/MainReviewProbe.java) 只针对上述固定提交，断言验证的是缺陷确实存在。它不加入正常测试任务；对应修复已由上面的正常回归测试覆盖，此程序保留用于历史 main 副本。程序建立独立小实例，不运行真实 Minecraft，不需要外部更新服务。

在 Windows 不区分大小写的文件系统中，先构建固定提交的副本，再运行：

```powershell
./docs/review/run-main-review.ps1 -Snapshot ./build/review-main-fb58e0a -Jdk E:/Java/jdk17-temurin
```

`Snapshot` 必须指向单独导出的 `fb58e0ae638575232931e77f9740fcb8dbb42b07`，而不是正在改动的开发工作区。副本应已完成 `allDist`，或至少具备 updater JAR 和 Fabric 1.20.1 模组 JAR。JDK 路径替换为自己的 Java 17 或更新版本。

脚本只编译复现源文件，并在副本 `build/main-review-<UUID>/` 中保留测试实例、class 和日志。关键结果如下；每行日志末尾还会输出对应实例的实际目录。

```text
CASE_RENAME: release=2, activeMod=false, disabledContent=new
PATH_ALIAS: acceptedPaths=2, pending=true, retryRecovery=failed, content=first-new
PROTECTED_ALIAS: options.txt/ accepted, protected=false, options.txt overwritten
OPTIONAL_TRANSITION: required->optional recorded=false, optional->required changes=0, modMissing=true
PENDING_HELPER_FAILURE: bootstrapReturned=true, pending=true, content=partially-installed
CORRUPT_HELPER: extractAttempts=2, cacheRepaired=false, cachedBytes=not-a-jar
All six probes reproduced the reviewed behavior.
```

### 覆盖范围与后续验证

审查覆盖全部生产 Java 类、适配层元数据、构建脚本、CI、现有文档和测试。原有端到端测试使用模拟游戏 JVM 与 JAR 锁；加载器入口测试构造上下文，不是完整游戏启动。因此不据此认定真实 Minecraft、不同启动器及所有平台均已兼容。

上述五项修复和六类场景已纳入持续验证。后续扩充恢复覆盖时，应包含操作前、备份后、替换后、基线提交后和恢复再次中断的状态。独立发布签名、完整依赖求解、自动清理和发布服务属于当前未实现的能力，没有作为这次缺陷计数。
