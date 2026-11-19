# 打包与服务派生流程

作者先通过支持 Modrinth 的启动器导出 `.mrpack`。将 ModpackUpdate 纳入整合包，推荐稳定目标文件名 `mods/ModpackUpdate.jar`，并在 `overrides/ModpackUpdate/config/config.json` 中设置 pack ID 和更新地址。

## 服务收到一个压缩包后的操作

1. 验证 ZIP、路径和 `modrinth.index.json`，读取版本号及 Minecraft / Loader 依赖，分配递增 `release-id`。更新公告由发布时填写。
2. 保留原索引字节，输出独立 `modrinth.index.json`。
3. 枚举 `overrides/`，再用 `client-overrides/` 覆盖相同路径。忽略 `server-overrides/`。对派生产物过滤 `ModpackUpdate/` 运行目录，保留其余最终客户端文件。
4. 按合并后的相对路径生成 `overrides.zip`，直接包含 `config/...` 等文件。计算每个条目的大小和 SHA-512，将完整条目列表写入发布元数据。集合为空时省略 ZIP，写 `present: false`。
5. 保存上传的 `.mrpack` 作为完整包兜底，计算完整包、索引和 overrides ZIP 的大小与 SHA-512。
6. 将更新公告、文件信息和三个产物 URL 写成发布元数据。上传并确认产物可取后，切换最新发布入口。

每版通常只有四个公开文件。

```text
12/
  release.json
  modrinth.index.json
  overrides.zip
  my-pack-1.2.0.mrpack
latest.json
```

`latest.json` 是 `release.json` 的当前版本内容。`overrides.zip` 可省略。普通模组继续使用索引中的下载地址，服务按需求提供镜像。所有产物都能由单个上传包派生，作者无需逐文件上传。

## 首次发行的基线

首发整合包应携带匹配当前版本的 `installed.json`，让玩家首次启动就知道已经安装哪个版本，并准确识别后续的本地修改。

服务可以从完整客户端文件映射直接生成该 JSON，过滤玩家保护路径和 `ModpackUpdate/`。对每个文件设置 `sha512` 与 `target-sha512` 为作者原始哈希、`retained` 为假。对可选文件仅记录实际随初始实例启用的项目。

如果服务负责补入出厂配置与基线，可以先从上传包派生最终文件映射，再把出厂记录写进供启动器导入的 `.mrpack` 的 `overrides/ModpackUpdate/config/`，最后计算完整包哈希。保留目录不参与 overrides 更新列表，因此不会形成基线哈希自引用。若上传包已有 `client-overrides/ModpackUpdate/config/`，必须同步更新或移除该旧副本，以免覆盖新出厂记录。

仓库提供本地 `seed` 命令，可从一个已经发布的固定版本生成出厂记录。

```powershell
java -jar build/dist/all/ModpackUpdate-updater-0.3.0-preview.3.jar seed "D:/Packs/MyPack" "https://updates.example.com/my-pack/12/release.json" "my-pack" "1.20.1" "0.16.10"
```

NeoForge 1.21.1 实例在末尾增加 `neoforge` 参数，并指定准确的加载器版本。

```powershell
java -jar build/dist/all/ModpackUpdate-updater-0.3.0-preview.3.jar seed "D:/Packs/MyNeoPack" "https://updates.example.com/my-neo-pack/12/release.json" "my-neo-pack" "1.21.1" "21.1.266" "neoforge"
```

省略最后一个参数时按 `fabric-loader` 处理。NeoForge 使用 `neoforge`，Forge 使用 `forge`，Quilt 使用 `quilt-loader`。参数中的游戏版本、加载器版本与索引依赖必须准确匹配。新增平台示例：

```powershell
java -jar build/dist/all/ModpackUpdate-updater-0.3.0-preview.3.jar seed "D:/Packs/MyForgePack" "https://updates.example.com/forge-pack/12/release.json" "forge-pack" "1.20.1" "47.4.28" "forge"
java -jar build/dist/all/ModpackUpdate-updater-0.3.0-preview.3.jar seed "D:/Packs/MyQuiltPack" "https://updates.example.com/quilt-pack/12/release.json" "quilt-pack" "1.21.1" "0.30.2" "quilt-loader"
```

四个平台继续使用同一套静态发布协议；`.mrpack` 选择对应平台的 ModpackUpdate JAR，分别维护更新源。

目标是作者的干净打包实例目录。命令从该版本的索引和 overrides 文件表生成哈希基线，仅下载元数据，不下载所有模组。`installed.json` 已存在时拒绝重建，避免误覆盖运行实例的差异记录。生成后将配置中的 `update-service-url` 改成 `latest.json`，把两个 JSON 放进发行包的 `overrides/ModpackUpdate/config/`，再次计算完整包大小与哈希。

## 更新前自检

- `.mrpack` 的 `versionId` 与发布 `version` 一致。
- Minecraft、加载器类型和加载器版本与需要更新的实例一致。
- 同一路径的索引文件被 overrides 覆盖时，发布 overrides 表记录最终内容。
- 旧模组文件名改变时，新清单只包含新文件名，客户端利用旧基线禁用原文件。
- 用户设置与存档路径已经配置保护，新增模组专用设置文件时同步补充作者配置。
- 完整包和单独索引使用同一版原始索引字节，避免兜底校验失败。
- 用一份干净实例和一份改过设置的实例分别试更新。

发布协议只接受 HTTPS 下载与文件操作，未提供远程脚本或服务端任意删除指令。
