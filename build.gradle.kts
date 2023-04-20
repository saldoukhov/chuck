import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    kotlin("multiplatform") version "1.8.20"
}

val ktorVersion: String by project

group = "us.saldoukhov"
version = "1.0.2"

repositories {
    mavenCentral()
}

kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
    binaries.all {
        freeCompilerArgs += "-Xdisable-phases=EscapeAnalysis"
    }
}

kotlin {
    val hostOs = System.getProperty("os.name")
    val hostArch = System.getProperty("os.arch")
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> if (hostArch == "aarch64") macosArm64("native") else macosX64("native")
        hostOs == "Linux" -> if (listOf("aarch64", "arm").any { hostArch.startsWith(it) }) linuxArm64("native") else linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }
    println("Building ${nativeTarget.konanTarget}")

    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }
    sourceSets {
        val nativeMac by creating {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:$ktorVersion")
            }
        }

        val nativeLinux by creating {
            dependencies {
                implementation("io.ktor:ktor-client-curl:$ktorVersion")
            }
        }

        val nativeWindows by creating {
            dependencies {
                implementation("io.ktor:ktor-client-winhttp:$ktorVersion")
            }
        }

        val nativeMain by getting {
            when (nativeTarget.konanTarget) {
                KonanTarget.LINUX_ARM64 -> dependsOn(nativeLinux)
                KonanTarget.LINUX_X64 -> dependsOn(nativeLinux)
                KonanTarget.MACOS_ARM64 -> dependsOn(nativeMac)
                KonanTarget.MACOS_X64 -> dependsOn(nativeMac)
                KonanTarget.MINGW_X64 -> dependsOn(nativeWindows)
                else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
            }
            dependencies {
                implementation("com.aallam.openai:openai-client:3.1.1")
                implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
                implementation("com.varabyte.kotter:kotter:1.1.0-rc4")
            }
        }
    }
}
