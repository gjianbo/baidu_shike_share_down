# 拾课分享下载扩展

拾课的独立 Android 扩展 APK。扩展在设备上运行 yt-dlp、Python 和 FFmpeg，接收 B 站、抖音、微博、X、TikTok、小红书和 YouTube 的视频分享链接，并将下载后的 MP4 交给拾课。拾课负责登录站点、记录任务和上传到百度或夸克网盘。

## 构建与安装

需要 JDK 17、Gradle 8.14.3 或兼容版本、Android SDK 36、NDK 28.2.13676358、CMake、Ninja 和 Python 3.12+。扩展支持 Android 10 及以上的 ARM64 设备。

```sh
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。用同一个 Android 调试密钥构建拾课主程序与扩展，再使用 `adb install -r` 将两个调试包安装到测试设备。系统分享菜单中选择「拾课分享下载」。拾课的「设置 → 扩展管理」展示安装状态。安装后在分享下载的「设置」菜单中选择目标网盘或配置平台 Cookie。站点要求登录时，也可在传输中心点击「登录并重试」。微博和 TikTok 从 0.2.0 起支持。小红书视频笔记及 YouTube 单条视频从 0.2.1 起支持，需要配套版本的拾课主程序。YouTube 可手动导入 `youtube.com` 的 Netscape Cookie。

本地正式构建读取同级 `baidu_player/local.properties` 中的签名配置，复用主程序的密钥库位置和别名。CI 或独立检出可以通过环境变量 `BDPLAYER_KEYSTORE`、`BDPLAYER_STORE_PASSWORD`、`BDPLAYER_KEY_ALIAS` 和 `BDPLAYER_KEY_PASSWORD` 配置签名，运行 `gradle :app:assembleRelease`。抖音等需要站点登录的视频仍需真机验收。

## 扩展设置

扩展通过 Android Manifest 的 `com.hnwd.bdplayer.SETTINGS_PAGES_V1` 声明设置页，值为按显示顺序排列的逗号分隔页面 ID。分享下载声明 `download,site_sessions`，分别对应下载设置与平台登录。主程序验证扩展签名后读取声明，显示已注册的页面，并保存下载位置和平台会话。支持旧版分享下载扩展的主程序也能显示这两个页面。

0.2.0 已在使用 4 KB 内存页的 ARM64 T700 上验证微博下载和百度网盘上传。从 0.2.1 起，构建会校验并内置固定版本的 yt-dlp 及其 YouTube 脚本，Android 端使用 APK 内的 QuickJS。Gradle 还使用固定源码和 Android NDK 重编 FFmpeg 内嵌的 WebP 库。打包检查会验证 ARM64 APK 中的 ELF 文件及运行时压缩包中的 ELF 文件均按 16 KB 对齐。[上游问题](https://github.com/yausername/youtubedl-android/issues/365)

## 独立发布

[扩展仓库](https://github.com/gjianbo/baidu_shike_share_down) 独立运行 [发布工作流](.github/workflows/extension-release.yml)。推送 `vX.Y.Z` tag 会构建签名 APK 并上传 Release。tag 必须匹配 `app/build.gradle` 的 `versionName`，扩展自己的 `versionCode` 需要递增。

在 GitHub Actions 手动运行时，留空 `release_tag` 会生成调试 APK，填写已有标签会生成签名 APK。这两种产物都可在运行页面的 Artifacts 下载。手动运行时勾选 `publish_release` 才会把签名 APK 上传到 Release。

本地可以运行 `bash .github/scripts/build-signed-extension.sh v0.2.1`，版本取当前源码。通过校验的正式包位于 `build/releases/`。

扩展 Actions 直接读取组织级 Secrets，使用与拾课主程序相同的签名。组织配置项为 `BDPLAYER_KEYSTORE_BASE64`、`BDPLAYER_STORE_PASSWORD`、`BDPLAYER_KEY_ALIAS` 和 `BDPLAYER_KEY_PASSWORD`。工作流校验 APK 的签名证书与已发布主包一致，并核对包名、版本与 ARM64 ABI，再将 `baidu-shike-share-download-arm64.apk` 上传到该 tag 的公开 Release。

主程序在「设置 → 扩展管理」提供下载安装和检查更新。它读取公开的稳定 Release，显示版本和下载大小，校验 APK 后由 Android 确认安装。

## 主程序接入

| 项目 | 值 |
| --- | --- |
| 扩展包名 | `com.hnwd.bdplayer_ext_share` |
| 主程序包名 | `com.hnwd.bdplayer` |
| 绑定服务 | `com.hnwd.bdplayer.extension.DownloadService` |
| 签名权限 | `com.hnwd.bdplayer_ext_share.BIND_DOWNLOAD`、`com.hnwd.bdplayer_ext_share.FORWARD_SHARE` |
| 分享转交 Activity | `com.hnwd.bdplayer.ExtensionShareActivity` |
| 分享转交参数 | Action `com.hnwd.bdplayer.action.EXTENSION_SHARE`、Extra `Intent.EXTRA_TEXT` |
| 协议版本 | `1` |

主程序显式绑定服务，调用 `capabilities()` 检查 `protocol`、`engine` 和 `sites`。两边的 [AIDL 定义](app/src/main/aidl/com/hnwd/bdplayer/extension/api/) 必须同步更新。

`start()` 接收任务 ID、尝试 ID、规范化分享链接、站点限定的 Netscape 格式 Cookie 文本、可写文件描述符和回调。访客请求传空 Cookie。主程序创建空暂存文件，调用返回后关闭自己的描述符。扩展最多执行一个任务，返回 `false` 时该请求未入队。取消使用原任务和尝试 ID。

| 回调 | 字段 |
| --- | --- |
| `PARSING`、`MERGING`、`COPYING` | 无 |
| `DOWNLOADING` | `percent`，整数 |
| `READY` | `name`、`format`、`bytes` |
| `LOGIN_REQUIRED`、`FAILED` | `code` |
| `CANCELLED` | 无 |

收到 `READY` 后，主程序核对尝试 ID、输出大小和音画轨，再把文件交给上传队列。收到其他终态或连接断开时，删除本次暂存文件。扩展在完成任务时关闭输出描述符，并清理私有目录和会话文件。

完整的发布与设备验收范围见 [分享下载分包方案](docs/分享下载分包方案.md)。
