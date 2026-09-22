# 群报数 Android 原生独立打卡 App 工程

本工程为纯原生 Android 工程，采用纯 Java 标���网络库（`HttpURLConnection`）开发，**不依赖任何浏览器 WebView，完全规避 CORS 跨域限制，支持在 Android 手机上脱离电脑独立运行！**

---

## 一、项目架构与特性
- **包名**：`com.qunbaoshu.assistant`
- **最低支持系统**：Android 5.0 (API 21) 至 Android 14+ (API 34)
- **体积**：编译后仅约 **1.5MB**
- **核心能力**：
  1. 纯黑 JPEG 字节流动态解码与阿里云 OSS STS 预签名表单直传；
  2. 范围坐标 GeoJSON Point 自动装配（偏差 0 米，自动合规）；
  3. 智能判断首次打卡（POST）与修改打卡（PUT）；
  4. 实时控制台文本日志与打卡结果大号卡片。

---

## 二、如何获取最终的 .apk 安装包？

### 途径 1：GitHub Actions 免费在线 1 键编译（最推荐，免装 Android Studio）
1. 在 GitHub 上新建一个仓库（公开或私有均可，如 `QunBaoShuApp`）；
2. 打开本项目根目录 `D:\AI\workspace\QunBaoShuApp`，执行推送：
   ```bash
   git init
   git add .
   git commit -m "init"
   git remote add origin https://github.com/你的用户名/你的仓库名.git
   git push -u origin main
   ```
3. 推送完成后，打开 GitHub 仓库页面的 **Actions** 标签页；
4. 你会看到名为 `Build Android APK` 的自动化流水线正在运行，大约 **1~2 分钟** 即可编译完成；
5. 在构建详情页面的 **Artifacts** 区域，直接点击下载 `QunBaoShu-Assistant-APK`，解压即为可直接在安卓手机上安装的 `app-debug.apk`！

---

### 途径 2：使用 Android Studio 本地打包
1. 打开 Android Studio，选择 **Open**，定位到 `D:\AI\workspace\QunBaoShuApp`；
2. 等待 Gradle 同步完成；
3. 点击顶部菜单 **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**；
4. 编译完成后点击右下角的 **locate**，即可取得生成好的 `app-debug.apk`。
