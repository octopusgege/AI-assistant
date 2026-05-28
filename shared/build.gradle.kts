import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties
import java.io.FileInputStream
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
}

val properties = Properties()
val localPropertiesFile = project.rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    properties.load(FileInputStream(localPropertiesFile))
}
val apiKey = properties.getProperty("LLM_API_KEY", "")
val apiUrl = properties.getProperty("LLM_API_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
val modelName = properties.getProperty("LLM_MODEL_NAME", "qwen-max")

val configGeneratedDir = File(layout.buildDirectory.get().asFile, "generated/source/config/com/development/ai_assistant/config")
configGeneratedDir.mkdirs()
File(configGeneratedDir, "AppConfig.kt").writeText("""
    package com.development.ai_assistant.config
    
    /**
     * 全局应用配置
     * 此文件由 Gradle 构建系统动态生成，实现了配置与代码的完全隔离
     */
    object AppConfig {
        const val apiKey = "$apiKey"
        const val apiUrl = "$apiUrl"
        const val modelName = "$modelName"
    }
""".trimIndent())

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
            implementation(libs.sqldelight.android)
            implementation(libs.koin.android)
            implementation(libs.ktor.client.okhttp)
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
            implementation(libs.voyager.navigator)
            implementation(libs.voyager.transitions)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(compose.materialIconsExtended)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        commonMain {
            kotlin.srcDir(File(layout.buildDirectory.get().asFile, "generated/source/config"))
        }
    }
}

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

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}