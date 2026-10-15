import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(21) }

sourceSets.main {
    // One copy of the UI, compiled into both apps: the desktop and Android Compose artifacts share the same API.
    kotlin.srcDir("../shared-ui/kotlin")
    resources.srcDir("../shared-ui/resources")
}

dependencies {
    implementation(project(":engine"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jcef)
    implementation(libs.dbus.core)
    implementation(libs.dbus.transport)
}

compose.desktop {
    application {
        mainClass = "app.soiboi.desktop.MainKt"
        // The embedded Chromium reaches into AWT's native window handles.
        jvmArgs += listOf(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.X11=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "soiboi"
            packageVersion = "2.1.0"
        }
    }
}
