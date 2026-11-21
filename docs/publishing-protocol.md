# 发布协议 v2（兼容 v1）

客户端配置的 `update-service-url` 指向当前发布的静态 JSON。服务只需接收一个 `.mrpack` 和版本公告，即可提取索引、派生客户端 overrides ZIP、生成元数据。初版客户端只消费这些文件，仓库不包含服务实现。

## 小型版本入口

从 0.3.0-preview.5 开始，推荐 `update-service-url` 指向以下 `latest.json`。启动检查只需要包标识、发布编号、版本号和安装清单引用；没有新版时不下载安装清单、Modrinth 索引、overrides 或完整包。

```json
{
  "protocol-version": 2,
  "pack-id": "my-pack",
  "release-id": 12,
  "version": "1.2.0",
  "manifest": {
    "url": "https://updates.example.com/my-pack/12/manifest.json",
    "sha512": "<128 位小写十六进制 SHA-512>",
    "size": 12345
  }
}
```

入口上限 64 KiB，`manifest` 上限 16 MiB，实际入口通常不足 1 KiB。v2 入口不应包含 `announcement`、`index`、`overrides`、`full-pack`。发现候选更新后才下载并校验安装清单，以显示公告；玩家确认前不下载模组文件。安装清单与入口的 `pack-id`、`release-id`、`version` 必须完全一致。

只增加一层整体安装清单，不按模组或 override 拆分小 JSON。安装清单复用以下 v1 完整元数据格式（`protocol-version: 1`），不能再次引用 `manifest`。包内 `modpackupdate.release.json` 的身份格式也继续为 `1`，与外部检查入口的协议版本分别定义。

旧的 v1 入口仍可直接使用；旧客户端不能读取 v2，应先通过 v1 渠道发布新版 ModpackUpdate，再切换入口，或为旧客户端保留独立 v1 URL。`full-pack-url` 兜底仅在入口下载失败时启用；入口或安装清单校验失败、安装清单 URL 不可用不会静默换源。

## 安装清单 manifest.json（兼容原 v1 元数据）

以下哈希和大小是占位示例，发布时必须根据产物的真实字节填入。JSON 字段使用短横线命名。未知扩展字段可以忽略。

```json
{
  "protocol-version": 1,
  "pack-id": "my-pack",
  "release-id": 12,
  "version": "1.2.0",
  "announcement": "更新了任务和模组。\n请在更新后重新启动游戏。",
  "index": {
    "url": "https://updates.example.com/my-pack/12/modrinth.index.json",
    "sha512": "<128 位小写十六进制 SHA-512>",
    "size": 12345
  },
  "overrides": {
    "present": true,
    "archive": {
      "url": "https://updates.example.com/my-pack/12/overrides.zip",
      "sha512": "<128 位小写十六进制 SHA-512>",
      "size": 34567
    },
    "files": [
      {
        "path": "config/example.json",
        "sha512": "<128 位小写十六进制 SHA-512>",
        "size": 128
      }
    ]
  },
  "full-pack": {
    "url": "https://updates.example.com/my-pack/12/my-pack-1.2.0.mrpack",
    "sha512": "<128 位小写十六进制 SHA-512>",
    "size": 45678
  }
}
```

| 字段 | 约束 |
| --- | --- |
| `protocol-version` | 固定整数 `1` |
| `pack-id` | 与本地配置完全一致，跨发布保持稳定 |
| `release-id` | 非负递增整数，只有大于本地记录才触发更新，避免重用已发布编号 |
| `version` | 与 Modrinth `versionId` 完全一致，1–80 个 ASCII 字母、数字、点、下划线、短横线，首位为字母或数字 |
| `announcement` | 可为空的纯文本，多行用 JSON 换行转义 |
| `index` | 独立的、原始字节未改写的 `modrinth.index.json` 下载信息 |
| `overrides.present` | 客户端最终 overrides 集合是否非空 |
| `overrides.archive` | `present` 为真时必填，ZIP 内路径直接相对于游戏根目录 |
| `overrides.files` | 客户端最终 overrides 的完整文件列表，可为空数组 |
| `full-pack` | 原 `.mrpack` 的下载信息，必填 |

没有 overrides 时使用 `"overrides": { "present": false, "files": [] }`。

所有大小单位为字节。产物与文件哈希均为 SHA-512，128 位小写十六进制。普通文件从 Modrinth 索引读取 SHA-512，索引依照 Modrinth 标准同时携带 SHA-1。每个产物或文件最多 20 GiB，发布元数据最多 16 MiB。所有 URL 使用 HTTPS，调试允许 `http://localhost` 或 `http://127.0.0.1`。重定向最多五次，每一跳重新检查协议。

HTTPS 与发布哈希用于传输和内容完整性校验。初版未实现独立发布签名，更新源是整合包作者指定的信任来源。

## Modrinth 解释规则

使用 `formatVersion: 1`、`game: "minecraft"`。`dependencies.minecraft` 与加载器版本必须与运行中的实例完全一致。各加载器使用以下字段：

| 实例加载器 | 索引依赖字段 |
| --- | --- |
| Fabric | `dependencies.fabric-loader` |
| NeoForge | `dependencies.neoforge` |
| Forge | `dependencies.forge` |
| Quilt | `dependencies.quilt-loader` |

一个发布只声明对应平台的加载器，跨游戏或加载器版本切换交给启动器。

NeoForge 21.1.266 的索引依赖部分如下。

```json
{
  "dependencies": {
    "minecraft": "1.21.1",
    "neoforge": "21.1.266"
  }
}
```

四个平台使用同一发布协议，均支持 v2 小型入口与 v1 完整清单。实例加载器由模组自动传给独立程序，作者为各个平台、游戏版本配置各自的更新源即可。Quilt 实例的索引声明 `quilt-loader`，即使包中包含 Fabric 模组，也不要同时声明 `fabric-loader`。安装前的重复模组 ID 检查优先读取 `quilt.mod.json`，没有该文件时读取 `fabric.mod.json`；Forge 读取 `META-INF/mods.toml`，NeoForge 读取 `META-INF/neoforge.mods.toml`。

索引的客户端 `env` 为 `unsupported` 时跳过，为 `optional` 时保留既有安装选择，新增可选内容默认不选。缺省按 `required` 处理。

先读取 `modrinth.index.json`，再按 `overrides/`、`client-overrides/` 的顺序覆盖同路径文件，得到客户端最终内容。`server-overrides/` 不进入客户端更新。派生 `overrides.zip` 已经合并两层，ZIP 内直接为 `config/...`、`mods/...` 等路径。压缩包内不再套一层 `overrides/`。

最终 overrides 的文件大小、哈希直接附在安装清单中，避免为每个 override 再发布一个文件。整个 overrides 集合无变动，或所有变动路径都受保护、被玩家跳过时，客户端不下载 ZIP。有需要安装的 override 时下载一次整包并复用，只提取选中的条目。

索引或 overrides 产物失败时，客户端下载 `full-pack`，从其中提取原始索引或合并后的客户端 override 条目，再按发布哈希验证。普通模组文件会依次尝试 `downloads` 中的地址。

标准 `.mrpack` 经常只含模组下载链接。完整包兜底能恢复索引和包内 overrides，外部模组仍须从其下载地址获得。服务如需备份这些下载内容，应提供可用的镜像 URL，并保证文件原始哈希一致。

## 路径与操作

路径以游戏根目录为基准、使用单个 `/` 分隔。文件路径禁止绝对路径、盘符、反斜杠、空路径段、结尾 `/`、`.`、`..`、Windows 设备名和大小写别名冲突。目标文件之间不能同时声明某路径及其子路径，例如 `config/a` 与 `config/a/b`。客户端验证文件最终位置及现有链接，防止写出实例目录。ZIP 只按已验证清单提取指定文件。

在 Windows 上，跨版本仅改变路径大小写时，客户端将它与旧基线视为同一个文件，避免把新版安装与旧版禁用拆成两项操作。新安装记录采用目标路径的拼写；玩家选择保留的内容仍保留原作者哈希。

发布描述的是完整目标状态。协议不含删除命令。客户端根据旧 `installed.json` 识别消失的受管 JAR，并执行禁用。清单中消失的其他文件保留。旧 JAR 被同路径新版替换时，先移到 `ModpackUpdate/{旧版本}/{事务编号}/mods/`，选中删除旧模组选项则在整批提交成功后清除该备份。

默认采用云端冲突操作；新增可选文件仍默认不选。采用新版模组时，客户端根据对应加载器的模组 ID 将其他文件名下的同 ID 本地 JAR 一并禁用，即使该文件没有旧基线。旧文件保留为 `.jar.disable`，无需额外复制，安装失败时参与同一事务回滚；取消新版模组则保留旧版。受保护文件不会被自动禁用。云端发布自身有重复 ID、或与其他保留的目标 JAR 仍冲突时拒绝安装。只读取顶层 `mods/*.jar` 的加载器元数据，不将整个目录逐个计算哈希；新增禁用操作仅对实际待禁用文件计算哈希。嵌套 JAR 和依赖关系仍交给加载器判断。

`ModpackUpdate/` 是客户端保留目录，更新安装阶段忽略发布清单中的这些路径。首次发行可通过 `.mrpack` 的 overrides 携带作者配置与初始 `installed.json`，它们由启动器首次导入。

## 安装基线

`ModpackUpdate/config/installed.json` 的记录由作者初始生成，之后由客户端维护。新版 `seed` 与成功安装写出 schema v2，小型头文件引用 `ModpackUpdate/config/file-indexes/{files-index}.json`，其中 `files-index` 是索引文件原始字节的 SHA-512：

```json
{
  "schema-version": 2,
  "pack-id": "my-pack",
  "release-id": 12,
  "version": "1.2.0",
  "transaction-id": "",
  "full-pack-fallback": false,
  "full-pack-sequence-known": true,
  "installation-status": "complete",
  "files-index": "<128 位小写十六进制 SHA-512>"
}
```

索引内容为 `{ "files": { "config/example.json": { "sha512": "…", "target-sha512": "…", "retained": false, "optional": false } } }`。启动检查只读取头文件；准备安装时验证并读取索引。先写入不可变索引，再原子替换头文件作为提交点；中断留下的未引用索引不会推进版本。目前保留旧索引，避免清理误伤恢复或人工回退。

以下旧 schema v1 内联记录仍可读取，成功安装后自动迁移；迁移前检查时跳过 `files` 的反序列化，但仍需顺序扫描旧 JSON。首次发行必须携带头文件和所引用的索引，不能只复制 `installed.json`。

```json
{
  "schema-version": 1,
  "pack-id": "my-pack",
  "release-id": 12,
  "version": "1.2.0",
  "transaction-id": "",
  "files": {
    "config/example.json": {
      "sha512": "<作者原版文件的哈希>",
      "target-sha512": "<最近发布的目标哈希>",
      "retained": false,
      "optional": false
    }
  }
}
```

`sha512` 是最后一次采用的作者原始内容哈希。`target-sha512` 是最近处理版本的目标哈希。玩家选择保留本地差异时，旧 `sha512` 保留，`target-sha512` 更新，`retained` 设为真。首次接管缺少基线的现有文件时，`sha512` 可为空，覆盖前必须由玩家选择。原始基线必须来自已安装版本的发行内容，不能用玩家当前磁盘状态冒充。

## 发布一致性

`optional` 记录客户端可选属性，缺省为假。内容哈希不变时也会同步最新属性，必需改为可选不会沿用旧的必需标记。新版将可选文件改为必需时，即使文件哈希相同也重新检查安装选择。

每个版本使用不可变 URL。先上传完整包、索引、overrides ZIP，再上传 `manifest.json`，最后生成小型 `release.json` 并原子切换 `latest.json`。保留旧版本元数据供已发行整合包生成初始基线。对同一 `release-id` 的内容修正应发布新编号。

格式依据 [Modrinth Modpack Format](https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack)。

## 不依赖接口清单的客户端兜底

客户端可选配置 `full-pack-url`，在发布元数据 URL 下载失败时直接读取完整 `.mrpack`。配置留空时维持原行为；清单已获取但内容校验失败时不启用。详情见 [配置文档](configuration.md#完整整合包兜底)。

推荐在完整包根目录加入 `modpackupdate.release.json`（最大 64 KiB），由服务从本次发布信息生成，不增加对外下载文件数量：

```json
{
  "protocol-version": 1,
  "pack-id": "my-pack",
  "release-id": 12,
  "version": "1.2.0"
}
```

其包标识、编号、版本必须与接口相同，`version` 还须与索引 `versionId` 相同。编号在所有来源中共用一个严格递增序列，不允许给旧内容重新分配更大的编号。先写入包内元数据，最后计算完整包大小与哈希。客户端安装后推进同一个本地编号，接口恢复时不能用较小编号回退。

普通 `.mrpack` 缺少该文件时仍可作为人工确认的候选源：只比较 `versionId` 是否不同，不推算顺序，也不允许静默安装。上一份安装来自无编号包时，切回接口的不同版本也必须确认；已存在但无效的内嵌元数据直接拒绝。该 URL 应持续提供最新、与实例游戏和加载器版本一致的包。

`.mrpack` 内的客户端 overrides 本地生成 SHA-512 文件表。内嵌身份信息并非数字签名；直接兜底下载仍信任作者配置的 HTTPS 来源，没有独立接口提供的预期整包哈希。普通外部文件继续校验索引声明的哈希。
