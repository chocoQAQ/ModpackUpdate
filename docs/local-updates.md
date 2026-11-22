# 本地更新与离线包打包

本地导入沿用完整包兜底的 Modrinth `.mrpack` 格式，不增加私有容器、额外发布清单或新的打包协议。

## 玩家使用

1. 将作者提供的 `.mrpack` 完整复制到 `<游戏根目录>/ModpackUpdate/inbox/`，再启动游戏。目录会自动创建；也可以自行创建。复制过程中可使用 `.mrpack.part` 后缀，完成后改为 `.mrpack`。
2. 一个包会直接进入校验；多个包会显示文件选择框，不根据文件名猜测哪个更新。只扫描顶层普通 `.mrpack` 文件，忽略子目录、符号链接和 `.part` 文件。
3. 检查完成后核对来源、版本与公告，确认后退出游戏，由独立更新器完成冲突选择和安装。即使 `silent-update: true`，本地包也必须确认。
4. 安装成功后原包移至 `ModpackUpdate/imported/{唯一编号}-{原文件名}`。用户选择保留部分差异后完成安装，也视为已处理并归档，可再次导入同版本包修复。

有本地包时优先本地流程，不请求云端更新入口或配置的完整包兜底地址。取消选择、拒绝确认、校验或安装失败时保留原包，本次不继续检查云端；删除或移出 inbox 后恢复通常的云端流程。安装文件全部准备并校验后才执行替换，缺少下载内容不会留下半套文件。

`enable` 控制自动云端检查；主动放入 inbox 的本地包仍会提示导入。必须先设置匹配的 `pack-id`，但 `update-service-url`、`full-pack-url` 可以留空。独立 `repair` 命令也优先读取 inbox，适合游戏无法正常启动的情况。

归档记录随 `installed.json` 的安装事务提交。若提交后中断，恢复完成或下次检查时继续归档；安装回滚不会归档新包。归档失败不会撤销已成功的安装，会显示/记录原因。原包在检查后被修改时保留 inbox 当前文件，避免移动另一份内容。`inbox/`、`imported/` 不参与下载缓存或备份保留清理。

## 普通包与完整离线包

两者的结构相同，区别在文件是否齐全：

| 包内容 | 安装行为 |
| --- | --- |
| 普通 `.mrpack`：索引主要是下载链接 | 复用当前文件和已校验缓存，缺少的文件按索引 URL 下载 |
| 完整离线包：最终客户端文件均在 overrides 中 | 从包内提取所需文件，无需访问更新服务或模组下载站 |

没有额外的“离线模式”字段。离线包若漏装某个索引文件，更新器会按普通包处理并尝试下载；网络不可用且无缓存则停止安装，保留原包。

```text
MyPack-1.3.0-offline.mrpack
  modrinth.index.json
  modpackupdate.release.json
  overrides/
    mods/
      ModpackUpdate.jar
      example-mod.jar
    resourcepacks/
      example.zip
  client-overrides/
    config/
      example.json
```

ZIP 根目录直接放索引，不能再套一层 `MyPack/`。不需要 `latest.json`、`manifest.json` 或单独的 `overrides.zip`。`server-overrides/` 不用于客户端；`client-overrides/` 覆盖 `overrides/` 中的同路径文件。

## 作者打包步骤

1. 从目标版本的完整 `.mrpack` 开始，在临时目录解包。索引必须描述完整目标状态，不能只列出本次改动，否则旧受管模组可能被判定为已移除并禁用。
2. 保留 `modrinth.index.json` 的全部有效字段、下载地址、哈希、文件大小及依赖版本。对每个客户端 `required` 文件下载原始内容，对照索引核验 SHA-512、SHA-1 和大小，再按索引的 `path` 放到 `overrides/<path>`。要让可选内容也能离线安装，应一并收齐客户端 `optional` 文件。不要收录 `client: unsupported` 的文件。
3. 保留作者原有的 overrides 和 client-overrides。如果同路径已有作者的最终覆盖内容，不能用索引下载文件盲目覆盖它；最终按 `client-overrides` 优先的规则取值。与索引字节、大小完全相同的内嵌副本仍保留索引的可选属性；不同内容的 override 按明确的客户端覆盖文件处理。
4. 确认所有最终客户端文件都有包内副本。仅把模组 JAR 放进去还不够：索引里的资源包等文件也可能需要下载。不要收录玩家存档、截图、账号信息、个人设置或 `ModpackUpdate/` 缓存及备份。
5. 推荐在根目录写入 `modpackupdate.release.json`，与云端、完整包兜底使用同一包标识、版本和递增编号：

```json
{
  "protocol-version": 1,
  "pack-id": "my-pack",
  "release-id": 13,
  "version": "1.3.0"
}
```

6. 用 ZIP 格式重新打包，文件后缀为 `.mrpack`。例如使用 JDK 的 `jar` 工具，将 `pack-root` 的内容放在压缩包根目录：

```powershell
jar --create --file MyPack-1.3.0-offline.mrpack --no-manifest -C ./pack-root .
```

Windows 上可用以下命令核对文件，结果与索引对应字段比较：

```powershell
Get-FileHash ./pack-root/overrides/mods/example-mod.jar -Algorithm SHA512
Get-FileHash ./pack-root/overrides/mods/example-mod.jar -Algorithm SHA1
(Get-Item ./pack-root/overrides/mods/example-mod.jar).Length
```

7. 用干净测试实例关闭网络，清空可丢弃的下载缓存，放入 inbox 验证安装与归档。不要仅在已有全部目标文件的开发实例上测试，否则可能漏掉缺失的包内文件。还应测试取消、保留冲突、同 ID 改名替换和更新器自身更新。

## 校验与版本处理

- Minecraft、加载器类型和版本必须与实例准确匹配，不能用本功能切换加载器或游戏版本。
- 带身份文件时严格匹配 `pack-id` 与索引 `versionId`；编号小于已安装编号，或相同编号对应不同版本时拒绝安装。同版本、同编号可以手动重装以修复文件。
- 没有身份文件的普通 `.mrpack` 可以导入，但无法验证包身份或版本顺序，必须由玩家核对来源并确认；保留现有发布编号，不凭版本名推算顺序。
- 玩家保护路径、保留目录、冲突勾选、备份与旧模组禁用规则与云端更新一致。将配置放进 `overrides/ModpackUpdate/config/` 不会覆盖现有客户端配置。
- 包文件与 ZIP 总展开大小均最多 20 GiB、最多 100,000 个 ZIP 条目，索引最多 16 MiB，身份文件最多 64 KiB。禁止路径穿越、重复条目及冲突路径。
- 检查先创建缓存副本，防止安装时读取仍在复制或被替换的源文件；检查期间检测到源文件变化会拒绝导入。检查沿用 `check-timeout-seconds`（默认 60 秒，可调至 600 秒），可在检查窗口跳过。
- 磁盘需容纳原包、检查副本、待安装文件及安装备份。缓存预算在安装空闲阶段执行，不会删除 inbox 或归档包。

完整包与内嵌身份信息不是独立数字签名，请分发作者可信来源的包。相关基础格式见 [发布协议](publishing-protocol.md) 与 [配置说明](configuration.md)。
