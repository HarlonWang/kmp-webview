# 自动发版（GitHub Actions → Maven Central）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** push 版本号 tag 即自动发布 5 个坐标到 Maven Central 并创建 GitHub Release；PR/main push 跑常规 CI。

**Architecture:** tag 驱动版本号（`ORG_GRADLE_PROJECT_VERSION_NAME` 覆盖，build 脚本不写死 version）；单 job `macos-latest` 跑 `publishAndReleaseToMavenCentral`；ubuntu 跑 Android/common 单测。详见 spec：`docs/superpowers/specs/2026-06-04-auto-publish-design.md`。

**Tech Stack:** GitHub Actions、vanniktech maven-publish 0.36.0、AGP KMP 插件（`withHostTestBuilder`）、gh CLI（secrets 配置）。

**前置状态说明:** 评审期间已在工作区预验证一处改动——`library/build.gradle.kts` 的 `withHostTestBuilder {}`（61 个 commonTest 用例在 JVM host 跑通）。执行 Task 1 时若工作区已含该改动，确认内容一致后直接提交即可。

---

### Task 1: library 启用 Android host 单测

**Files:**
- Modify: `library/build.gradle.kts:25-27`

背景：新版 AGP KMP 插件默认不创建 Android host 单测任务，library 的 commonTest 此前只能在 iOS 模拟器（macOS）执行。启用后 ubuntu CI 可跑 `testAndroidHostTest`。

- [ ] **Step 1: 在 kotlin.android 块加 withHostTestBuilder**

`library/build.gradle.kts` 中 `withJava()` 之后加：

```kotlin
        withJava()

        withHostTestBuilder {}

        compilations.configureEach {
```

- [ ] **Step 2: 运行 host 单测验证**

Run: `./gradlew :library:testAndroidHostTest`
Expected: BUILD SUCCESSFUL；`library/build/test-results/testAndroidHostTest/*.xml` 含 7 个测试类共 61 个用例，0 失败

- [ ] **Step 3: Commit**

```bash
git add library/build.gradle.kts
git commit -m "build(test): library 启用 Android host 单测（withHostTestBuilder）"
```

---

### Task 2: 版本号 tag 驱动改造

**Files:**
- Modify: `library/build.gradle.kts:13-14`（group/version 行）、`library/build.gradle.kts` mavenPublishing 块
- Modify: `scanner/build.gradle.kts:11-12`（group/version 行）、`scanner/build.gradle.kts` mavenPublishing 块

- [ ] **Step 1: library 删硬编码、coordinates 显式声明**

删除 `library/build.gradle.kts` 中：

```kotlin
group = "wang.harlon"
version = "0.1.0"
```

`mavenPublishing` 块中：

```kotlin
    coordinates(group.toString(), "kmp-webview", version.toString())
```

改为：

```kotlin
    coordinates(groupId = "wang.harlon", artifactId = "kmp-webview")
```

- [ ] **Step 2: scanner 同样改造**

删除 `scanner/build.gradle.kts` 中：

```kotlin
group = "wang.harlon"
version = "0.1.0"
```

`mavenPublishing` 块中：

```kotlin
    coordinates(group.toString(), "kmp-webview-scanner", version.toString())
```

改为：

```kotlin
    coordinates(groupId = "wang.harlon", artifactId = "kmp-webview-scanner")
```

- [ ] **Step 3: 验证版本号从 property 流入**

```bash
rm -rf ~/.m2/repository/wang/harlon
./gradlew publishToMavenLocal -PVERSION_NAME=0.0.0-local
ls ~/.m2/repository/wang/harlon/kmp-webview/ ~/.m2/repository/wang/harlon/kmp-webview-scanner/
```

Expected: 两个目录下均为 `0.0.0-local`（无 `unspecified`、无 `0.1.0`）

- [ ] **Step 4: 验证日常构建不受影响**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL（项目依赖不看版本号，version=unspecified 无影响）

- [ ] **Step 5: Commit**

```bash
git add library/build.gradle.kts scanner/build.gradle.kts
git commit -m "build(publish): 版本号改为 tag 驱动，移除硬编码 group/version"
```

---

### Task 3: 常规 CI workflow（build.yml）

**Files:**
- Create: `.github/workflows/build.yml`

- [ ] **Step 1: 创建 build.yml**

```yaml
name: Build

on:
  pull_request:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Install JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: 17

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build and test
        run: ./gradlew :library:testAndroidHostTest :scanner:testDebugUnitTest :androidApp:assembleDebug
```

注：ubuntu 无法编译 Kotlin/Native Apple target，iOS 编译由发版时 macOS runner 兜底（spec §3）。

- [ ] **Step 2: 本地预验 CI 命令**

Run: `./gradlew :library:testAndroidHostTest :scanner:testDebugUnitTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 同步修正 spec §3 任务名**

spec `docs/superpowers/specs/2026-06-04-auto-publish-design.md` §3 中：

```yaml
      - run: ./gradlew :library:testDebugUnitTest :scanner:testDebugUnitTest :androidApp:assembleDebug
```

改为：

```yaml
      - run: ./gradlew :library:testAndroidHostTest :scanner:testDebugUnitTest :androidApp:assembleDebug
```

（`:library:testDebugUnitTest` 在新版 AGP KMP 插件下不存在，host 单测任务为 `testAndroidHostTest`，依赖 Task 1。）

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/build.yml docs/superpowers/specs/2026-06-04-auto-publish-design.md
git commit -m "ci: 新增常规构建 workflow（PR/main 跑单测 + sample 编译）"
```

---

### Task 4: 发版 workflow（publish.yml）

**Files:**
- Create: `.github/workflows/publish.yml`

- [ ] **Step 1: 创建 publish.yml**

```yaml
name: Publish Release

on:
  push:
    tags:
      - '[0-9]+.[0-9]+.[0-9]+*'

jobs:
  publish:
    runs-on: macos-latest
    permissions:
      contents: write   # gh release create 需要
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Install JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: 17

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Publish to Maven Central
        run: ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
        env:
          ORG_GRADLE_PROJECT_VERSION_NAME: ${{ github.ref_name }}
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.SONATYPE_NEXUS_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.SONATYPE_NEXUS_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_PASSWORD }}

      - name: Create GitHub Release
        run: gh release create "$GITHUB_REF_NAME" --generate-notes
        env:
          GH_TOKEN: ${{ github.token }}
```

- [ ] **Step 2: 语法校验**

Run: `ruby -ryaml -e "YAML.load_file('.github/workflows/publish.yml'); puts 'YAML OK'"`
Expected: `YAML OK`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/publish.yml
git commit -m "ci: 新增发版 workflow（push tag 自动发布 Maven Central + GitHub Release）"
```

---

### Task 5: 配置 4 个 repo secrets

**Files:** 无文件改动（GitHub 仓库设置）

凭证从 HarlonWang/secrets 取，管道直传 `gh secret set`，不落盘不回显（spec §4）。

- [ ] **Step 1: 写入 4 个 secret**

```bash
R=HarlonWang/kmp-webview
S=repos/HarlonWang/secrets/contents/maven-publishing
gh api "$S/gpg-private-key.b64" --jq '.content' | base64 -d | gh secret set SIGNING_PRIVATE_KEY -R "$R"
gh api "$S/gpg-passphrase.txt"  --jq '.content' | base64 -d | tr -d '\n' | gh secret set SIGNING_PASSWORD -R "$R"
gh api "$S/sonatype-token.txt"  --jq '.content' | base64 -d | sed -n 1p | tr -d '[:space:]' | gh secret set SONATYPE_NEXUS_USERNAME -R "$R"
gh api "$S/sonatype-token.txt"  --jq '.content' | base64 -d | sed -n 2p | tr -d '[:space:]' | gh secret set SONATYPE_NEXUS_PASSWORD -R "$R"
```

- [ ] **Step 2: 验证**

Run: `gh secret list -R HarlonWang/kmp-webview`
Expected: 列出 `SIGNING_PRIVATE_KEY`、`SIGNING_PASSWORD`、`SONATYPE_NEXUS_USERNAME`、`SONATYPE_NEXUS_PASSWORD` 4 条

---

### Task 6: 推送并验证常规 CI

- [ ] **Step 1: push main**

```bash
git push origin main
```

- [ ] **Step 2: 等待 build.yml 跑绿**

```bash
gh run watch -R HarlonWang/kmp-webview --exit-status $(gh run list -R HarlonWang/kmp-webview --workflow=build.yml -L 1 --json databaseId --jq '.[0].databaseId')
```

Expected: `✓ ... completed with 'success'`

注：publish.yml 无法演习，下次真实发版（push 下一个版本 tag）时验证；发版后按 spec §7 用 curl 复查 repo1.maven.org 五坐标可用性。
