import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Code shared with the Android app lives in ../shared/src.
sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin", "../shared/src")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.json:json:20240303")          // same JSON API Android has built in
    implementation("org.apache.pdfbox:pdfbox:3.0.3")  // PDF export
}

compose.desktop {
    application {
        mainClass = "com.example.infinitecanvas.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "InfiniteCanvas"
            packageVersion = "2.0.0"
            description = "Infinite drawing canvas"
            vendor = "Novaillusion"
            includeAllModules = true
            windows {
                iconFile.set(project.file("icons/app.ico"))
                menu = true
                menuGroup = "Infinite Canvas"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                // Keep this fixed so new versions upgrade the installed one.
                upgradeUuid = "6f1f1c2e-6b8e-4c8f-9a3d-2b7f0c5e9a41"
            }
        }
    }
}
