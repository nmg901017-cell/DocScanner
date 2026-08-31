# 使用华为云镜像加速，完整构建 Android APK
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV JAVA_HOME=/opt/jdk17
ENV PATH=${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

# 安装基础工具
RUN apt-get update && apt-get install -y \
    wget unzip curl git python3 \
    && rm -rf /var/lib/apt/lists/*

# 安装 OpenJDK 17 (华为镜像)
RUN mkdir -p /opt && \
    cd /opt && \
    wget -q "https://repo.huaweicloud.com/OpenJDK17/17.0.2%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.2_8.tar.gz" -O openjdk17.tar.gz && \
    tar -xzf openjdk17.tar.gz && \
    mv jdk-17.0.2+8 /opt/jdk17 && \
    rm openjdk17.tar.gz

# 安装 Android SDK (使用华为镜像)
RUN mkdir -p /opt/android-sdk/cmdline-tools && \
    cd /tmp && \
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O cmdline-tools.zip && \
    unzip -q cmdline-tools.zip && \
    mv cmdline-tools /opt/android-sdk/cmdline-tools/latest && \
    rm cmdline-tools.zip

# 接受许可证并安装 SDK 组件
RUN yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses || true
RUN /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;34.0.0"

# 复制项目
WORKDIR /workspace
COPY . /workspace/

# 授权 gradlew
RUN chmod +x gradlew

# 构建 APK
RUN ./gradlew assembleDebug --no-daemon

# 输出 APK 位置
RUN ls -lh app/build/outputs/apk/debug/

CMD ["echo", "APK built successfully at app/build/outputs/apk/debug/app-debug.apk"]
