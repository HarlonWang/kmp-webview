# iOS Demo App

iOS 演示工程占位。等 `:library` 能成功 `assembleKmpWebViewXCFramework` 后，再创建 Xcode 工程：

```bash
./gradlew :library:assembleKmpWebViewReleaseXCFramework
```

产物位于 `library/build/XCFrameworks/release/KmpWebView.xcframework`，可直接拖入 Xcode 工程引用。
