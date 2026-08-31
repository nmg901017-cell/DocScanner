# 华为文档扫描 APK - 构建指南

使用 HMS ML Kit 文档扫描 + 文字识别

## 方式一：Android Studio（最简单，推荐）

### 步骤 1：安装 Android Studio
- 下载地址：https://developer.android.com/studio
- 安装时选择包含 Android SDK 的完整安装

### 步骤 2：打开项目
1. 打开 Android Studio
2. 选择 "Open an existing project"
3. 选择 `DocScanner` 文件夹
4. 等待 Gradle 同步完成（首次需要下载依赖，可能需要几分钟）

### 步骤 3：配置 HMS
1. 注册华为开发者账号：https://developer.huawei.com/consumer/cn/
2. 创建应用，获取 AppGallery Connect 配置信息
3. 在 `app/build.gradle` 中替换 `applicationId` 为你的包名

### 步骤 4：构建 APK
1. 点击 Android Studio 右上角的 ▶️ 运行按钮
2. 选择连接的 Android 手机或模拟器
3. 或者点击 Build → Build Bundle(s) / APK(s) → Build APK 生成独立 APK 文件

---

## 方式二：命令行构建（适合有经验者）

### 环境要求
- JDK 17+
- Android SDK (API 34)
- Gradle 8.4

### 构建命令

```bash
# 进入项目目录
cd DocScanner

# Linux/Mac 给 gradlew 执行权限
chmod +x gradlew

# 构建 debug APK
./gradlew assembleDebug

# APK 输出位置：app/build/outputs/apk/debug/app-debug.apk
```

---

## HMS ML Kit 配置说明

### 1. 添加 HMS 仓库
项目 `settings.gradle` 已包含华为仓库地址：
```
maven { url 'https://developer.huawei.com/repo/' }
```

### 2. HMS AppGallery Connect 配置
首次运行需要在华为开发者网站配置：
1. 登录 https://developer.huawei.com/consumer/cn/
2. 进入 "我的应用" → 创建应用
3. 获取 `client_id` 和 `app_id`
4. 下载 `agconnect-services.json` 文件
5. 放入项目 `app/` 目录

### 3. 依赖说明
- `mlkit-document-scanner:1.0.3.001` - 文档扫描
- `mlkit-text-recognition:1.0.5.301` - 文字识别（OCR）

---

## 应用功能

✅ 文档扫描（边缘检测+透视矫正）
✅ 多页扫描
✅ 文字识别（OCR）
✅ 图片保存到相册
✅ 中文识别优化

---

## 常见问题

### Q: 构建失败，提示 "Could not resolve HMS"
A: 检查网络连接，确保可以访问华为 Maven 仓库

### Q: 提示 "No HMS Core installed"
A: 在华为应用市场下载 "HMS Core"（华为移动服务）

### Q: OCR 识别率低
A: 确保扫描时光线充足，文档平整清晰
