import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

android {
    namespace = "wang.harlon.webview.scanner"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.animation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.materialIconsExtended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

mavenPublishing {
    publishToMavenCentral()
    // CI 注入 signingInMemoryKey 时启用签名；本地无密钥跳过
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    configure(AndroidSingleVariantLibrary(variant = "release"))
    coordinates(groupId = "wang.harlon", artifactId = "kmp-webview-scanner")

    pom {
        name.set("kmp-webview-scanner")
        description.set("Optional QR scanner extension for kmp-webview: CameraX + ZXing full-screen scanner (Android).")
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
