# 文档扫描 APK（离线版）

纯离线文档扫描 + 文字识别，**不依赖 Google 服务 / 华为服务**，适合国产 ROM。

## 功能
- 📷 相机拍摄文档（全分辨率）
- ✂️ 自动边缘检测 + 透视矫正（OpenCV）
- 🔍 图像增强，让扫描件清晰
- 🔤 离线 OCR 文字识别（Tesseract，支持中文）
- 💾 保存到相册

## 技术栈
- **OpenCV 4.9.0** - 文档边缘检测/透视矫正/图像增强
- **Tesseract4Android 4.9.0** - 离线 OCR（中文+英文）
- **Kotlin + AndroidX** - 现代 Android UI

## GitHub Actions 自动构建
项目含 `.github/workflows/build.yml`，推送到 GitHub 后自动构建 APK：
1. 进入仓库 Actions 页
2. 等构建完成（约 5-8 分钟）
3. 在 Artifacts 下载 `app-debug.apk`

## 使用方式
1. 打开 App，点"开始扫描"
2. 对准文档拍照
3. 自动校正（裁剪+拉正+增强）
4. 点"识别文字"提取内容
5. 点"保存图片"存到相册

## 说明
- OCR 引擎首次使用会下载中文+英文训练数据（约 40MB），之后完全离线可用
- 支持 Android 7.0+ (API 24)
- 无需任何云服务/账号
