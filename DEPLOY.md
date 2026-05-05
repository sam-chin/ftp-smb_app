# 部署到 GitHub 指南

## 步骤 1: 初始化 Git 仓库

```bash
cd d:\Temp\lingma\program1
git init
```

## 步骤 2: 添加所有文件

```bash
git add .
```

## 步骤 3: 提交代码

```bash
git commit -m "Initial commit: LAN Media Player project"
```

## 步骤 4: 创建 GitHub 仓库

1. 访问 https://github.com/new
2. 创建一个新的仓库（例如：`lan-media-player`）
3. **不要**初始化 README、.gitignore 或 license（我们已经有了）

## 步骤 5: 关联远程仓库

```bash
git remote add origin https://github.com/YOUR_USERNAME/lan-media-player.git
```

将 `YOUR_USERNAME` 替换为你的 GitHub 用户名，`lan-media-player` 替换为你创建的仓库名。

## 步骤 6: 推送到 GitHub

```bash
git branch -M main
git push -u origin main
```

## 步骤 7: 查看 GitHub Actions

推送完成后：
1. 访问你的 GitHub 仓库页面
2. 点击 "Actions" 标签
3. 你会看到 Android CI 工作流正在运行
4. 等待构建完成（通常需要 5-10 分钟）
5. 构建成功后，可以在 Actions 页面下载生成的 APK 文件

## 注意事项

⚠️ **重要**: 项目需要 `gradle-wrapper.jar` 文件才能正常构建。

如果 GitHub Actions 构建失败，提示找不到 gradle-wrapper.jar，请执行：

```bash
# 在本地生成 gradle wrapper
gradle wrapper

# 或者手动下载
curl -L https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradle-wrapper.jar -o gradle/wrapper/gradle-wrapper.jar

# 然后重新提交
git add gradle/wrapper/gradle-wrapper.jar
git commit -m "Add gradle wrapper jar"
git push
```

## 故障排除

### 问题 1: Gradle 构建失败

检查 `.github/workflows/android-ci.yml` 中的 JDK 版本是否正确。

### 问题 2: 依赖下载失败

GitHub Actions 会自动缓存 Gradle 依赖，首次构建可能较慢。

### 问题 3: APK 未生成

确保 `app/build.gradle` 配置正确，并且没有编译错误。

## 后续开发

每次修改代码后：

```bash
git add .
git commit -m "描述你的修改"
git push
```

GitHub Actions 会自动触发新的构建！
