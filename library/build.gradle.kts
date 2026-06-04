import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "wang.harlon"
version = "0.1.0"

val frameworkName = "KmpWebView"
val xcframework = XCFramework(frameworkName)

kotlin {
    android {
        namespace = "wang.harlon.webview"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava()

        withHostTestBuilder {}

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }

        androidResources.enable = true
    }

    iosArm64()
    iosSimulatorArm64()

    targets.withType(KotlinNativeTarget::class.java).configureEach {
        binaries.framework {
            baseName = frameworkName
            isStatic = true
            binaryOption("bundleId", "wang.harlon.kmp-webview.KmpWebView")
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "kmp-webview", version.toString())

    pom {
        name.set("kmp-webview")
        description.set("Kotlin Multiplatform WebView SDK with TopAppBar and bottom navigation.")
        url.set("https://github.com/HarlonWang/kmp-webview")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("HarlonWang")
                name.set("HarlanWang")
                url.set("https://github.com/HarlonWang")
            }
        }
        scm {
            url.set("https://github.com/HarlonWang/kmp-webview")
            connection.set("scm:git:git://github.com/HarlonWang/kmp-webview.git")
            developerConnection.set("scm:git:ssh://git@github.com/HarlonWang/kmp-webview.git")
        }
    }
}
