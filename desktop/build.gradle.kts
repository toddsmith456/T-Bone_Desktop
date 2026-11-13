import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "social.tbone"
version = "0.3.36-desktop.1"

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
        freeCompilerArgs.add("-opt-in=androidx.compose.ui.ExperimentalComposeUiApi")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle.prov)
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.simple)

    // JUnit 4 only: `kotlin("test")` would pull a second runner, and `runBlocking`
    // (used by the persistence tests) already comes from kotlinx-coroutines-core above.
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "social.tbone.desktop.MainKt"
        jvmArgs += listOf("-Dapple.awt.application.appearance=system", "-Xmx1g")
        nativeDistributions {
            // Deb is the primary target. Rpm is listed so the packageRpm task
            // exists; it is built and published by the release workflow. Windows
            // (.msi) and macOS (.dmg) are deliberately absent: jpackage can only
            // build for the host OS, so listing them here would create tasks that
            // can never succeed on a Linux machine.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)

            // JDK modules the app actually touches. Without this, jpackage infers a
            // module set from static analysis and omits java.sql, so the packaged app
            // dies at startup with NoClassDefFoundError: java/sql/DriverManager when
            // the SQLite JDBC driver initialises. Listed explicitly because the driver
            // is loaded reflectively and inference cannot see it.
            modules(
                "java.sql",          // SQLite JDBC
                "java.naming",       // required by java.sql
                "java.security.jgss",
                "java.instrument",
                "jdk.unsupported",   // sun.misc.Unsafe, used by SQLite JDBC + OkHttp
                "jdk.crypto.ec",     // EC crypto used by BouncyCastle / TLS
                "java.desktop",      // AWT/Swing for Compose
                "java.management",
                "jdk.management",
            )
            packageName = "t-bone-desktop"
            packageVersion = "0.3.36"
            description = "T-Bone Desktop: privacy-first Nostr client (desktop port of T-Bone v0.3.36)"
            vendor = "T-Bone Desktop contributors"
            copyright = "MIT License. Portions Copyright (c) 2026 daomah (bony), T-Bone contributors."
            linux {
                // Registers the app with the desktop menu via `xdg-desktop-menu
                // install`, which is what puts an entry in a launcher menu. On a
                // minimal container without the `menu`/`desktop-file-utils` packages
                // that call exits 3 and dpkg reports the package as failed even though
                // the app files are in place — see docs/BUILD.md for the host
                // prerequisite. Kept enabled because silently dropping menu
                // integration to make an install log look clean would be the wrong
                // trade.
                shortcut = true
                appCategory = "Network"
                debMaintainer = "toddsmith456 <toddsmith456@users.noreply.github.com>"
            }
        }
    }
}

/**
 * The tests are JUnit 4 (`org.junit.Test`), which is what upstream's test sources
 * use. `useJUnitPlatform()` would silently run none of them, so the JUnit 4 runner
 * is selected explicitly. `failOnNoDiscoveredTests` is left at its default so an
 * empty suite fails the build instead of looking green.
 */
tasks.withType<Test> {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The persistence and key-handling suites open real SQLite databases and do
    // AES-GCM work; the crypto suite does secp256k1. A modest heap is enough, and
    // keeping it modest matters: the whole test JVM is OOM-killed on a 2 GB
    // machine if Gradle's daemon and the worker both claim large heaps.
    maxHeapSize = "384m"
    maxParallelForks = 1
}

/**
 * Writes the runtime classpath to build/runtime-classpath.txt.
 *
 * Used by scripts/run-dev.sh so the app can be launched directly with `java`
 * (no Gradle daemon) during development and for the headless smoke test.
 */
tasks.register("printRuntimeClasspath") {
    doLast {
        val out = layout.buildDirectory.file("runtime-classpath.txt").get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            (listOf(
                layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath,
                layout.buildDirectory.dir("resources/main").get().asFile.absolutePath,
            ) + configurations.getByName("runtimeClasspath").files.map { it.absolutePath })
                .joinToString(File.pathSeparator)
        )
        println("wrote ${out.absolutePath}")
    }
}
