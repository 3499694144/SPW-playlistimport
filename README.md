# 歌单导入 · Salt Player for Windows

把「歌名 - 歌手」文本歌单导入 SPW 本地歌单。每行一首，**歌单名直接取 TXT 文件名**。

当前版本 **v2.0.0**，按 **SPW 1.19.2** 适配，宿主符号与接口已在该版本上核对。

## v2.0.0 更新

- **适配新版播放器。** 1.19.2 改了内部混淆名，旧版写死的版本检查会直接拒绝运行；现在不再按版本号拦人，改为在运行时按语义解析宿主符号，并校验每一个方法签名。以后小版本升级也有机会直接可用。
- **按歌名 + 歌手匹配。** 读取每行「歌名 - 歌手」，与电脑曲库的标题、艺术家标签比对；支持多歌手、`/ & , ;` 分隔、全角/半角、大小写、重音、括号版本（Live / Remix / 2017 版）等差异。
- **歌单名就是文件名。** 不再需要手动输入歌单名；`我的歌单.txt` 直接创建「我的歌单」。
- **先预览、再创建。** 需要处理的歌曲（未找到、同名不唯一、歌手不一致、文件不可读）会列在窗口里；近似匹配会标成「建议复核」，可以点开改选，也可以直接创建。
- **兼容旧格式。** 之前椒盐音乐移动端导出的「每行一个绝对路径」TXT 仍然可以导入。

## 歌单格式

UTF-8 文本，每行一首：

```text
网易歌单
慢热 - 满舒克
Angel - 尹美莱 / Tiger JK / 비지 (Bizzy)
胆小鬼 - 梁咏琪
Don't Play - Sik-K / PUNCHNELLO
```

- 也支持制表符分隔：`慢热<Tab>满舒克`；分隔符还可以是 `–`、`—` 或 `|`。
- 只有歌名、没有歌手时，会按唯一的歌名匹配。
- 第一行如果是「网易歌单」「我的歌单」这类标题会自动忽略；`#` 开头的首行同理。
- 旧的路径格式（`/storage/emulated/0/Music/xxx.flac`，每行一个）仍然支持，按文件名匹配。

## 安装和使用

1. 在播放器中将电脑上的音乐文件加入曲库，完成扫描。
2. 打开 **创意工坊 → 模组管理 → 导入模组**，选择 `spw.playlistimport.spmod`（与旧版同名，会直接覆盖旧插件目录）。
3. 启用模组，授予 **读取曲库** 和 **修改界面** 两项权限。
4. 打开 **歌单导入 → 配置 → 选择歌单文件**，选你的 TXT。
5. 窗口会显示歌单名、汇总和需要处理的歌曲；点异常歌曲行可以指定曲库里的文件。
6. 点 **创建歌单**，再点 **确认创建**。未处理的记录会自动跳过，重复歌曲只导入一次。

### 本地安装（可选）

已经装过旧版、又不想在界面里导入时，可以先完全退出播放器，再执行：

```powershell
python tools/build.py --spw-dir "D:\SteamLibrary\steamapps\common\Salt Player for Windows" `
  --install-root "$env:APPDATA\Salt Player for Windows\workshop\plugins"
```

旧版会备份到 `workshop\plugin-backups\`（不会留在插件扫描目录里），新插件在下次启动时加载。

## 匹配规则

按下面顺序尝试，命中即停：

1. 歌名和歌手集合完全一致 → 直接匹配。
2. 歌名一致且至少一位歌手相同 → 直接匹配。
3. 去掉括号版本后歌名一致且歌手相同 → 建议复核（用于 Live / Remix / 年份版差异）。
4. 曲库标题是歌名的前缀，且歌手相同 → 建议复核。
5. 曲库标题里带歌手名（如 `Apologize-OneRepublic`）→ 建议复核。
6. 歌名只差一两个字且歌手相同 → 建议复核。
7. 只有歌名时按唯一标题匹配；歌名唯一但歌手不一致 → 需手动确认，不会乱猜。

「建议复核」的歌曲已经选中，会照常导入；它们只是列出来让你确认版本。点开改选后就会从列表消失。

## 构建

不依赖 Gradle / JitPack：直接用播放器自带的 fat jar 和 Kotlin 2.4.20 编译，再用 Python 打包。

```powershell
python tools/prepare_toolchain.py --spw-dir "D:\SteamLibrary\steamapps\common\Salt Player for Windows"
python tools/prepare_compose.py
python tools/build.py --spw-dir "D:\SteamLibrary\steamapps\common\Salt Player for Windows"
```

产物：`build/libs/spw.playlistimport.spmod`（文件名与旧版一致，便于直接覆盖升级）。

- `prepare_toolchain.py` 下载 Kotlin 2.4.20，并复制宿主的 `app/ffmpeg-x64.dll` 作为编译类路径。
- `prepare_compose.py` 只下载编译用的 Compose 1.12.1（其中 runtime 来自 Google Maven 的 `androidx.compose.runtime`）。
- 如果本机已有工具链，可以跳过前两步：

```powershell
python tools/build.py --host-jar "D:\path\to\ffmpeg-x64.dll" `
  --kotlin-home "D:\kotlin\kotlinc" --compose-dir "D:\compose-jars"
```

检查脚本：

```powershell
# 用真实曲库导出的 TSV 跑一遍匹配，输出汇总和待处理行
python tools/dump_library.py "$env:APPDATA\Salt Player for Windows\spw.db" library.tsv
javac --release 25 -encoding UTF-8 -cp build\plugin-classes -d build\check tools\MatcherCheck.java
java -cp "build\check;build\plugin-classes" local.saltplaylist.MatcherCheck library.tsv "我的歌单.txt"
```

## 版本绑定

- 数据库、歌单 DAO、封面和界面 Hook 都按字节码语义查找，没有写死 1.19.2 的混淆名。
- 解析成功后会缓存到 `workshop/data/spw.playlistimport/host-symbols.properties`，宿主文件指纹变化时自动重扫。
- 如果新版播放器改了歌单写入接口，插件会报「无法确认当前播放器的歌单写入接口」并停止，而不是写坏数据。

## 已知限制

- 只读取 UTF-8 文本；GBK 文件请先另存为 UTF-8。
- 只匹配电脑曲库里已有的歌；曲库没有的歌会列入「曲库未找到」，需要先在播放器里扫描。
- 歌名相同但歌手完全不同的翻唱/现场版不会自动猜，需要手动确认。
- 歌单封面取第一首匹配歌曲的内嵌封面；没有封面时用默认图。
- 状态与日志只写本机 `workshop/data/spw.playlistimport/`。

## 许可证

项目代码采用 [Apache License 2.0](LICENSE)。
