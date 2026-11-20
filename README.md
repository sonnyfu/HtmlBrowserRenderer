# HTML 默认浏览器即时渲染器 (HtmlBrowserRenderer)

传入或粘贴 HTML 代码（或从任意 App 点击「分享」发送文本到本应用），即可立即拉起手机系统默认浏览器进行完整渲染。

## 快速获取 APK 的两种方式

### 方式一：本地一键编译 APK
如果你电脑上装有 Android Studio 或 Android SDK：
1. 解压本项目并用 Android Studio 打开，点击 **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**。
2. 或者在命令行执行：
   ```bash
   gradle assembleDebug
   ```
   生成的安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 方式二：免本地环境，使用 GitHub Actions 自动云端打包 APK
本项目已内置 `.github/workflows/build-apk.yml`：
1. 将解压后的文件上传至任意 GitHub 仓库（公开或私有均可）。
2. 进入仓库的 **Actions** 标签页，等待 `Build Android APK` 工作流执行完成（约 1~2 分钟）。
3. 在工作流底部的 **Artifacts** 区域直接下载编译并签名好的 `HtmlBrowserRenderer-APK` 安装包。
