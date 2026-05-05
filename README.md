# LAN Media Player

一个基于 Android Kotlin 的局域网媒体播放器应用，支持 SMB 和 FTP 协议。

## 功能特性

- ✅ 支持 FTP 协议（原生 Socket 实现）
- ✅ 支持 SMB 协议（使用 SMBJ 库）
- ✅ 基于 ExoPlayer 的媒体播放
- ✅ HTTP 代理服务器将网络文件流转换为 HTTP 流
- ✅ Jetpack Compose 现代化 UI
- ✅ 四层架构设计（UI / Controller / Network / Player）

## 技术栈

- **Kotlin**: 1.8.22
- **Android Gradle Plugin**: 7.4.2
- **compileSdk**: 34
- **minSdk**: 24
- **ExoPlayer**: 2.19.1
- **SMBJ**: 0.11.5
- **Jetpack Compose**: 2023.06.01

## 项目结构

```
app/src/main/java/com/example/lanmediaplayer/
├── MainActivity.kt              # UI 层 - 主 Activity
├── controller/
│   └── MediaController.kt       # Controller 层 - 媒体控制
├── network/
│   ├── FtpClient.kt             # Network 层 - FTP 客户端
│   ├── SmbClient.kt             # Network 层 - SMB 客户端
│   └── HttpProxyServer.kt       # Network 层 - HTTP 代理
└── ui/theme/
    └── Theme.kt                 # UI 层 - 主题配置
```

## 构建说明

### 本地构建

```bash
# Windows
gradlew.bat build

# Linux/Mac
./gradlew build
```

### GitHub Actions 自动构建

项目已配置 GitHub Actions，推送到 `main` 分支时会自动触发构建。

构建流程：
1. 检出代码
2. 设置 JDK 11
3. 授予 gradlew 执行权限
4. 执行 Gradle 构建
5. 运行测试
6. 上传 APK 文件

## 使用方法

1. 打开应用
2. 选择协议（FTP 或 SMB）
3. 输入服务器地址、端口、用户名和密码
4. 点击连接
5. 浏览文件并点击媒体文件进行播放

## 注意事项

- 需要 INTERNET 权限
- 支持明文流量（用于局域网访问）
- 媒体文件通过 HTTP 代理方式播放，避免直接将 SMB/FTP 流传给播放器

## License

MIT License
