# 自动发版（GitHub Actions → Maven Central）设计

- 日期：2026-06-04
- 状态：已评审
- 背景：0.1.0 首版已通过本地 `publishAndReleaseToMavenCentral` 链路（vanniktech maven-publish 0.36.0 + Central Portal）人工发布验证。本设计将发版自动化：push tag 即发版，参照 quickjs-wrapper 的 tag 触发模式。

## 决策摘要

| 决策点 | 结论 |
|---|---|
| 版本号来源 | tag 驱动，tag 名即版本号（裸版本号风格，如 `0.1.0`），唯一来源 |
| 本地版本号 | 极简派：不设 `VERSION_NAME` 默认值，本地 version 为 `unspecified`，本地不发布 |
| Central release | 全自动（`publishAndReleaseToMavenCentral`），校验通过即发布，不登 Portal |
| Release notes | GitHub 自动生成（`--generate-notes`，汇总两 tag 间 PR/commit） |
| 范围 | 发版 workflow（publish.yml）+ 常规 CI（build.yml）一起配 |
| 拓扑 | 单 workflow 单 job，`macos-latest` 一次发全部 5 个坐标（一个 deployment） |

## 1. 版本号流转

**改造**（消除"显式赋值优先级高于 property、CI 覆盖失效"的问题）：

- `library/build.gradle.kts`、`scanner/build.gradle.kts` 删除 `group = "wang.harlon"` / `version = "0.1.0"` 两行
- `coordinates()` 改为显式传 groupId + artifactId，**不传 version**：
  ```kotlin
  coordinates(groupId = "wang.harlon", artifactId = "kmp-webview")        // library
  coordinates(groupId = "wang.harlon", artifactId = "kmp-webview-scanner") // scanner
  ```
- `gradle.properties` 不新增任何条目

**流转**：

- CI 发版：`ORG_GRADLE_PROJECT_VERSION_NAME=${{ github.ref_name }}` → vanniktech 插件读 `VERSION_NAME` property 设置 `project.version` → tag 名即版本号
- 本地日常：version 为 `unspecified`，build/test/androidApp（项目依赖）均不受影响；`publishToMavenLocal` 产物不可用——属预期，发布只走 tag
- 本地临时验证发布配置：`./gradlew publishToMavenLocal -PVERSION_NAME=0.0.0-local`

## 2. publish.yml（发版 workflow）

```yaml
name: Publish Release
on:
  push:
    tags: ['[0-9]+.[0-9]+.[0-9]+*']   # 0.1.0 / 1.2.3-rc1；拦住非版本 tag
jobs:
  publish:
    runs-on: macos-latest              # library 含 iosArm64/iosSimulatorArm64，必须 macOS
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4    # temurin 17
      - uses: gradle/actions/setup-gradle@v4   # quickjs 用的 gradle-build-action 已弃用
      - run: ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
        env:
          ORG_GRADLE_PROJECT_VERSION_NAME: ${{ github.ref_name }}
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.SONATYPE_NEXUS_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.SONATYPE_NEXUS_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_PASSWORD }}
      - run: gh release create "$GITHUB_REF_NAME" --generate-notes
        env: { GH_TOKEN: '${{ github.token }}' }
```

要点：

- `publishAndReleaseToMavenCentral` 为插件自带任务（上传 → 等校验 → 自动 release），无需改 build 脚本 DSL
- `SIGNING_PRIVATE_KEY` 存 base64 编码的 armored 私钥（secrets 仓库 `gpg-private-key.b64`），Gradle `useInMemoryPgpKeys` 可直接消费（quickjs-wrapper CI 验证过同样用法）
- GitHub Release 在发布成功之后创建（步骤顺序保证：Maven 发布失败则无 Release）

## 3. build.yml（常规 CI）

```yaml
name: Build
on:
  pull_request:
  push: { branches: [main] }
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4    # temurin 17
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :library:testDebugUnitTest :scanner:testDebugUnitTest :androidApp:assembleDebug
```

- ubuntu 无法编译 Kotlin/Native Apple target，故只跑 Android/common 单测 + sample 编译；iOS 编译由发版时 macOS runner 兜底（低频库可接受，不为每个 PR 燃 macOS 时长）

## 4. Secrets 配置（一次性）

按 secrets 仓库（HarlonWang/secrets `maven-publishing/`）既有约定，为 kmp-webview 配 4 个 repo secret：

| Secret | 来源文件 |
|---|---|
| `SIGNING_PRIVATE_KEY` | `gpg-private-key.b64` |
| `SIGNING_PASSWORD` | `gpg-passphrase.txt` |
| `SONATYPE_NEXUS_USERNAME` | `sonatype-token.txt` 第一行 |
| `SONATYPE_NEXUS_PASSWORD` | `sonatype-token.txt` 第二行 |

实施方式：`gh api ... | gh secret set`，管道传输，凭证不落盘、不回显。

## 5. 错误处理与边界

| 场景 | 行为 |
|---|---|
| tag 不符合版本格式 | pattern 不匹配，workflow 不触发 |
| Central 校验失败 | Gradle 任务失败 → workflow 红，不建 GitHub Release |
| 同版本号重发 | Central 拒绝已发布版本，任务失败（预期）；删 tag 重打新版本号 |
| 上传中途失败 | 重跑 workflow；残留的未发布 deployment 在 Portal 手动 Drop |
| 本地误跑发布任务 | 版本为 `unspecified` 且本地无凭证，跑不通（双保险） |
| GitLab 镜像 | tag 同步过去但 GitLab 侧无 CI 配置，无影响 |

## 6. 发版操作手册（自动化后）

```bash
git tag 0.1.1 && git push origin 0.1.1
```

即完整发版：Maven Central 5 个坐标 + GitHub Release（自动 notes）。

## 7. 验证

- build.yml：合入后首个 push/PR 即验证
- 版本号改造：本地 `./gradlew publishToMavenLocal -PVERSION_NAME=0.0.0-local`，确认产物版本正确
- publish.yml：无法演习（发了即真发），下次真实发版时验证；发版后用 `curl repo1.maven.org/.../<artifact>-<ver>.pom` 复查 5 坐标可用性
