import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile // 👉 修复点：引入 JVM 编译任务类

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    androidLibrary {
        namespace = "com.development.ai_assistant.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // 👉 修复点：移除了这里原本引发报错的 compilerOptions 代码块

        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            // Android 平台的 SQLite 驱动
            implementation(libs.sqldelight.android)
            implementation(libs.koin.android)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // 导航
            implementation(libs.voyager.navigator)
            implementation(libs.voyager.transitions)
            // 依赖注入
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(compose.materialIconsExtended)
            // SQLDelight 协程扩展
            implementation(libs.sqldelight.coroutines)
            //时间
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")

        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

//  配置 SQLDelight
sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.development.ai_assistant.database")
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

//
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}