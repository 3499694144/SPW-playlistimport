package local.saltplaylist;

/** User-facing text for the import dialog. Settings text lives in preference_config.json. */
public final class UiText {
    private UiText() {}

    public static final String APP = "歌单导入";
    public static final String PICK_PLAYLIST = "选择椒盐音乐移动端导出的歌单";
    public static final String SUPPORTED_VERSION = "此插件仅适配 Salt Player for Windows %s";
    public static final String ENABLE_FIRST = "请先在模组管理中启用歌单导入插件。";
    public static final String NEED_PERMISSIONS = "请停用插件后重新启用，勾选“允许读取曲库”和“允许修改应用行为与界面”权限。";
    public static final String LOADING = "正在读取歌单和曲库，请稍候……";
    public static final String IMPORTING = "正在创建歌单……";
    public static final String FAILED = "导入未完成：%s";
    public static final String RESOLUTION_FAILED = "无法确认当前播放器的歌单写入接口，已停止导入。详情：%s";
    public static final String EMPTY_FILE = "歌单中没有歌曲路径。";
    public static final String TOO_LARGE = "歌单过大；最多支持 10 MiB、50,000 条路径。";
    public static final String INVALID_ENCODING = "无法按 UTF-8 读取文件。请选择椒盐音乐导出的原始 TXT 歌单。";
    public static final String INVALID_LINE = "第 %d 行不是有效的绝对文件路径。";
    public static final String TITLE_LABEL = "歌单名称";
    public static final String DEFAULT_TITLE = "导入歌单";
    public static final String TITLE_REQUIRED = "请填写新歌单名称。";
    public static final String SUMMARY = "共 %d 条 · 可导入 %d 首 · 将跳过 %d 条 · 重复 %d 条";
    public static final String MANUAL = "选择文件";
    public static final String IMPORT = "创建歌单";
    public static final String CANCEL = "取消";
    public static final String PICK_TRACK = "为这首歌指定曲库中的歌曲";
    public static final String NOT_IN_LIBRARY = "所选文件未在当前曲库中唯一找到。请先将文件加入播放器曲库并扫描，再重新预览。";
    public static final String NOT_READABLE = "所选歌曲当前不可读或文件不存在。";
    public static final String NO_MATCHES = "没有可导入的歌曲。请先将音乐文件加入电脑曲库。";
    public static final String CONFIRM = "创建歌单“%s”，导入 %d 首歌曲。\n自动跳过 %d 条未匹配记录和 %d 条重复记录。\n是否继续？";
    public static final String DONE = "已创建歌单“%s”，导入 %d 首歌曲。\n自动跳过 %d 条未匹配记录和 %d 条重复记录。";
    public static final String CHANGED_LIBRARY = "曲库或文件已发生变化，请关闭此窗口后重新预览。受影响歌曲：%s";
    public static final String DISABLED = "插件已停用，操作已取消。";
    public static final String CAPTURE_FAILED = "未捕获到宿主曲库实例，已停止导入。";
    public static final String NO_LIVE_WRITE = "未能确认事务结果，请先检查播放器中的歌单再重试。";
    public static final String DESCRIPTION = "导入的歌单";
    public static final String BACK = "返回检查";
    public static final String CONFIRM_IMPORT = "确认创建";
    public static final String NO_WINDOW = "请从播放器主窗口的“模组管理 → 歌单导入 → 配置”打开导入。";
    public static final String COVER_FAILED = "歌单已创建，但封面更新失败。请先查看歌单，不要重复导入。";
    public static final String NO_ARTWORK = "第一首歌曲没有可解码的封面，歌单使用默认封面。";

    public static String status(PlaylistMatcher.Status status) {
        return switch (status) {
            case MATCHED -> "已匹配";
            case MANUAL -> "手动指定";
            case MISSING -> "曲库未找到";
            case AMBIGUOUS -> "多个同名文件";
            case UNAVAILABLE -> "文件不可读";
        };
    }

}
