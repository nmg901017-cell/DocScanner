#!/bin/bash
# DocScanner APK 构建设置脚本
# 适用于 Ubuntu/Debian/Linux Mint 等 apt 系统

set -e

echo "===== 文档扫描 APK 构建环境设置 ====="

# 1. 安装 Java JDK 17
echo "[1/4] 安装 JDK 17..."
if command -v java &> /dev/null && java -version 2>&1 | grep -q "17"; then
    echo "JDK 17 已安装，跳过"
else
    sudo apt-get update
    sudo apt-get install -y openjdk-17-jdk-headless
fi

# 2. 安装 Android SDK
echo "[2/4] 安装 Android SDK..."
if [ -d "$ANDROID_HOME" ] || [ -d "$ANDROID_SDK_ROOT" ]; then
    echo "Android SDK 已安装，跳过"
else
    export ANDROID_HOME=$HOME/android-sdk
    mkdir -p $ANDROID_HOME

    # 下载 Android command line tools
    cd /tmp
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O cmdline-tools.zip
    unzip -q cmdline-tools.zip
    mkdir -p $ANDROID_HOME/cmdline-tools
    mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest
    rm cmdline-tools.zip

    # 接受许可证并安装 SDK
    yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses || true
    $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

    echo "export ANDROID_HOME=$ANDROID_HOME" >> ~/.bashrc
    echo "export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools" >> ~/.bashrc
fi

# 3. 设置项目
echo "[3/4] 设置项目..."
cd "$(dirname "$0")"
chmod +x gradlew

# 4. 构建 APK
echo "[4/4] 构建 APK..."
./gradlew assembleDebug

echo ""
echo "===== 构建完成 ====="
echo "APK 文件位置: app/build/outputs/apk/debug/app-debug.apk"
