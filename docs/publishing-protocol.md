# 发布协议 v1

客户端配置的 `update-service-url` 指向当前发布的静态 JSON。服务只需接收一个 `.mrpack` 和版本公告，即可提取索引、派生客户端 overrides ZIP、生成元数据。初版客户端只消费这些文件，仓库不包含服务实现。

## 最新发布元数据

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

四个平台使用同一发布协议 v1。实例加载器由模组自动传给独立程序，作者为各个平台、游戏版本配置各自的更新源即可。Quilt 实例的索引声明 `quilt-loader`，即使包中包含 Fabric 模组，也不要同时声明 `fabric-loader`。安装前的重复模组 ID 检查优先读取 `quilt.mod.json`，没有该文件时读取 `fabric.mod.json`；Forge 读取 `META-INF/mods.toml`，NeoForge 读取 `META-INF/neoforge.mods.toml`。

索引的客户端 `env` 为 `unsupported` 时跳过，为 `optional` 时保留既有安装选择，新增可选内容默认不选。缺省按 `required` 处理。

先读取 `modrinth.index.json`，再按 `overrides/`、`client-overrides/` 的顺序覆盖同路径文件，得到客户端最终内容。`server-overrides/` 不进入客户端更新。派生 `overrides.zip` 已经合并两层，ZIP 内直接为 `config/...`、`mods/...` 等路径。压缩包内不再套一层 `overrides/`。

最终 overrides 的文件大小、哈希直接附在发布元数据中，避免为每个 override 再发布一个文件。整个 overrides 集合无变动，或所有变动路径都受保护、被玩家跳过时，客户端不下载 ZIP。有需要安装的 override 时下载一次整包并复用，只提取选中的条目。

索引或 overrides 产物失败时，客户端下载 `full-pack`，从其中提取原始索引或合并后的客户端 override 条目，再按发布哈希验证。普通模组文件会依次尝试 `downloads` 中的地址。

标准 `.mrpack` 经常只含模组下载链接。完整包兜底能恢复索引和包内 overrides，外部模组仍须从其下载地址获得。服务如需备份这些下载内容，应提供可用的镜像 URL，并保证文件原始哈希一致。

## 路径与操作

路径以游戏根目录为基准、使用 `/`。禁止绝对路径、盘符、反斜杠、`.`、`..`、Windows 设备名和大小写别名冲突。客户端验证文件最终位置及现有链接，防止写出实例目录。ZIP 只按已验证清单提取指定文件。

发布描述的是完整目标状态。协议不含删除命令。客户端根据旧 `installed.json` 识别消失的受管 JAR，并执行禁用。清单中消失的其他文件保留。旧 JAR 被同路径新版替换时，先移到 `ModpackUpdate/{旧版本}/{事务编号}/mods/`，选中删除旧模组选项则在整批提交成功后清除该备份。

`ModpackUpdate/` 是客户端保留目录，更新安装阶段忽略发布清单中的这些路径。首次发行可通过 `.mrpack` 的 overrides 携带作者配置与初始 `installed.json`，它们由启动器首次导入。

## 安装基线

`ModpackUpdate/config/installed.json` 的记录由作者初始生成，之后由客户端维护。

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

`optional` 记录客户端可选属性，缺省为假。新版将可选文件改为必需时，即使文件哈希相同也重新检查安装选择。

每个版本使用不可变 URL。先上传完整包、索引、overrides ZIP，再生成并上传当前版本元数据，最后原子切换 `latest.json`。保留旧版本元数据供已发行整合包生成初始基线。对同一 `release-id` 的内容修正应发布新编号。

格式依据 [Modrinth Modpack Format](https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack)。

## 不依赖接口清单的客户端兜底

客户端可选配置 `full-pack-url`，在发布元数据 URL 下载失败时直接读取完整 `.mrpack`。此功能无需修改发布协议，也无需生成额外服务文件。配置留空时维持原行为；清单已获取但内容校验失败时不启用。详情见 [配置文档](configuration.md#完整整合包兜底)。

该 URL 应持续提供最新、与当前实例游戏和加载器版本一致的包。兜底以包内 `versionId` 是否不同判断候选更新，不推算 `release-id` 或比较版本先后。`.mrpack` 内的客户端 overrides 本地生成 SHA-512 文件表；下载包只受 HTTPS 来源与 ZIP/路径/大小检查保护，没有接口提供的预期整包哈希。普通外部文件继续校验索引声明的哈希。
