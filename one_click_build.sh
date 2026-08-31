#!/bin/bash
# DocScanner 一键构建脚本 - 包含所有依赖下载
# 用法: bash one_click_build.sh

set -e
set -x  # 显示调试信息

ANDROID_SDK_DIR="$HOME/android-sdk-home"
GRADLE_VERSION="8.4"
GRADLE_DIR="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"

echo "===== DocScanner 一键构建脚本 ====="
echo "此脚本会自动下载所有需要的工具..."

# 创建目录
mkdir -p "$ANDROID_SDK_DIR"
mkdir -p "$HOME/.gradle/wrapper/dists"

# 检测系统架构
ARCH=$(uname -m)
echo "检测到系统架构: $ARCH"

# ===== 第1步：安装 Java JDK =====
if ! command -v java &> /dev/null; then
    echo "[1/4] 安装 OpenJDK 17..."
    if command -v apt-get &> /dev/null; then
        sudo apt-get update -qq
        sudo apt-get install -y -qq openjdk-17-jdk-headless wget unzip
    elif command -v yum &> /dev/null; then
        sudo yum install -y java-17-openjdk java-17-openjdk-devel wget unzip
    else
        echo "不支持的包管理器，请手动安装 JDK 17"
        exit 1
    fi
else
    echo "[1/4] Java 已安装: $(java -version 2>&1 | head -1)"
fi

export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
echo "JAVA_HOME=$JAVA_HOME"

# ===== 第2步：下载 Gradle =====
GRADLE_ZIP="/tmp/gradle-${GRADLE_VERSION}-bin.zip"
if [ ! -f "$GRADLE_ZIP" ] || [ ! -d "/tmp/gradle-${GRADLE_VERSION}" ]; then
    echo "[2/4] 下载 Gradle ${GRADLE_VERSION}..."
    cd /tmp
    wget -q --timeout=300 "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -O "$GRADLE_ZIP" || {
        echo "Gradle 下载失败，尝试华为镜像..."
        wget -q --timeout=300 "https://repo.huaweicloud.com/gradle/gradle-${GRADLE_VERSION}-bin.zip" -O "$GRADLE_ZIP"
    }
    unzip -q -o "$GRADLE_ZIP" -d /tmp/
fi
export GRADLE_HOME="/tmp/gradle-${GRADLE_VERSION}"
export PATH="$GRADLE_HOME/bin:$PATH"
echo "[2/4] Gradle 已安装: $(gradle --version | head -1)"

# ===== 第3步：下载 Android SDK =====
export ANDROID_HOME="${ANDROID_SDK_DIR}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

if [ ! -d "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "[3/4] 下载 Android SDK Command Line Tools..."
    cd /tmp
    wget -q --timeout=180 "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O cmdline-tools.zip
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q -o cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools/"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm cmdline-tools.zip
fi

# 接受协议并安装 SDK 组件
echo "[3/4] 安装 Android SDK 组件..."
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;34.0.0" > /dev/null 2>&1

echo "[3/4] Android SDK 安装完成"

# ===== 第4步：构建项目 =====
echo "[4/4] 构建 APK..."
cd "$(dirname "$0")"

# 给 gradlew 执行权限
chmod +x gradlew

# 构建 debug APK
GRADLE_USER_HOME="$HOME/.gradle" ./gradlew assembleDebug --no-daemon --info 2>&1 | tail -50

echo ""
echo "===== 构建完成 ====="
ls -lh app/build/outputs/apk/debug/app-debug.apk 2>/dev/null && echo "APK 路径: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
