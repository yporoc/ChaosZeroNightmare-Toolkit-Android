// 项目级构建脚本：声明插件版本（实际版本定义在 gradle/libs.versions.toml）
// 注意：AGP 9.0 起 Kotlin 支持已内置，不要再应用 org.jetbrains.kotlin.android
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
