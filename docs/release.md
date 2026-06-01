# 发布说明

[English](release.en.md) | 中文

本文档说明 `vexra-ldb` 的发布准备和 Maven 发布流程。实际发布前请先确认版本号、标签、测试和签名配置。

## 发布前检查

1. 确认工作区干净。
2. 更新 `CHANGELOG.md`，把 `Unreleased` 内容归档到目标版本。
3. 确认 README、设计文档和 API 文档与代码一致。
4. 运行完整测试：

```bash
./gradlew clean test
```

Windows PowerShell:

```powershell
.\gradlew.bat clean test
```

5. 生成本地发布产物：

```bash
./gradlew clean publishToMavenLocal
```

## 0.2.0 发布前验证记录

- 版本号：`gradle.properties` 已切换为 `version=0.2.0`。
- 测试：已在 Windows PowerShell 执行 `.\gradlew.bat clean test`，结果通过。
- 本地发布：已执行 `.\gradlew.bat clean publishToMavenLocal`，结果通过。
- 本地 Maven 产物：已确认 `vexra-ldb-0.2.0.jar`、`sources.jar`、`javadoc.jar`、`.pom`、`.module` 以及对应 `.asc` 签名文件生成。
- CI：已纳入 `.github/workflows/ci.yml`，覆盖 Ubuntu/Windows JDK 8 `clean test` 和 Ubuntu `clean publishToMavenLocal`。
- 变更记录：`CHANGELOG.md` 已将本轮发布内容归档到 `0.2.0`。

## 签名配置

项目会读取根目录下未提交的 `signing.properties`。该文件已在 `.gitignore` 中忽略，不能提交到仓库。

示例：

```properties
signing.keyId=xxxxxxxx
signing.password=your-password
signing.secretKeyRingFile=/path/to/secring.gpg

ossrhUsername=your-username
ossrhPassword=your-password
```

## 发布仓库配置

发布仓库可以通过 Gradle property 指定：

```properties
snapshotsRepository=https://s01.oss.sonatype.org/content/repositories/snapshots/
releasesRepository=https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/
```

如果没有配置上述属性，Gradle 会发布到 `build/repos/snapshots` 或 `build/repos/releases`，用于本地验证，不会上传远端仓库。

## 版本发布

当前项目使用 `net.researchgate.release` 插件，发布配置要求分支匹配 `master|v.*`。

典型流程：

```bash
./gradlew release
```

发布后需要确认：

- Git tag 已创建，格式为 `v${version}`。
- Maven 产物包含主 jar、sources jar 和 javadoc jar。
- POM 中包含项目名称、描述、许可证、开发者、SCM 和 issue tracker。
- 发布仓库中的签名文件完整。

## 回滚

- 如果远端发布失败但未公开，可删除 staging repository 或清理本地 `build/repos`。
- 如果 tag 已推送但产物未发布，应删除错误 tag 并重新发布。
- 如果产物已公开发布，不应覆盖同一版本；请发布新的修复版本。
