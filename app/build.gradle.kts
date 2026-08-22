plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun signingValue(name: String): String? =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orNull
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("LLMHOST_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("LLMHOST_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("LLMHOST_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("LLMHOST_RELEASE_KEY_PASSWORD")
val releaseStoreFileResolved = releaseStoreFile?.let { rootProject.file(it) }
// Usable requires both configured values AND an on-disk keystore, so a stale
// ~/.gradle/gradle.properties cannot hard-fail every release assembly.
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null } && releaseStoreFileResolved?.exists() == true
val kleidiAiEnabled = signingValue("LLMHOST_ENABLE_KLEIDIAI")
    ?.let { value ->
        value.equals("true", ignoreCase = true) ||
            value.equals("on", ignoreCase = true) ||
            value.equals("yes", ignoreCase = true) ||
            value == "1"
    }
    ?: true
val vulkanEnabled = signingValue("LLMHOST_ENABLE_VULKAN")
    ?.let { value ->
        value.equals("true", ignoreCase = true) ||
            value.equals("on", ignoreCase = true) ||
            value.equals("yes", ignoreCase = true) ||
            value == "1"
    }
    ?: true
val openClRequested = signingValue("LLMHOST_ENABLE_OPENCL")
    ?.let { value ->
        value.equals("true", ignoreCase = true) ||
            value.equals("on", ignoreCase = true) ||
            value.equals("yes", ignoreCase = true) ||
            value == "1"
    }
    ?: false
val openClIncludeDir = signingValue("LLMHOST_OPENCL_INCLUDE_DIR")
val openClLibrary = signingValue("LLMHOST_OPENCL_LIBRARY")
val openClAvailable = openClRequested && openClIncludeDir != null && openClLibrary != null
val openClCmakeArgs = buildList {
    add("-DLLMHOST_ENABLE_OPENCL=${if (openClAvailable) "ON" else "OFF"}")
    if (openClAvailable) {
        add("-DOpenCL_INCLUDE_DIR=$openClIncludeDir")
        add("-DOpenCL_LIBRARY=$openClLibrary")
    }
}

android {
    namespace = "com.prismai.llmhost"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.prismai.llmhost"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Prism Local"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20")
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-29",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DLLMHOST_ENABLE_KLEIDIAI=${if (kleidiAiEnabled) "ON" else "OFF"}",
                    "-DLLMHOST_ENABLE_VULKAN=${if (vulkanEnabled) "ON" else "OFF"}"
                )
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "DEVELOPER_WORK_MODE", "false")
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
            manifestPlaceholders["appLabel"] = "Prism Local"
        }

        create("dev") {
            dimension = "distribution"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "DEVELOPER_WORK_MODE", "true")
            buildConfigField("String", "DISTRIBUTION", "\"dev\"")
            manifestPlaceholders["appLabel"] = "Prism Dev"
        }
    }

    if (releaseSigningReady) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    } else if (releaseStoreFile != null && releaseStoreFileResolved?.exists() != true) {
        logger.lifecycle(
            "Release signing disabled: LLMHOST_RELEASE_STORE_FILE is set to '$releaseStoreFile' " +
                "but the keystore file does not exist. Building UNSIGNED release; restore the " +
                "keystore or update ~/.gradle/gradle.properties before shipping."
        )
    } else {
        logger.lifecycle(
            "Release signing disabled: set LLMHOST_RELEASE_STORE_FILE, " +
                "LLMHOST_RELEASE_STORE_PASSWORD, LLMHOST_RELEASE_KEY_ALIAS, and " +
                "LLMHOST_RELEASE_KEY_PASSWORD as Gradle properties or environment variables."
        )
    }

    buildTypes {
        // debug: sideload-friendly, debug hooks on, no minify. Package: com.prismai.llmhost.debug
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "LLMHOST_DEBUG_HOOKS", "true")
            buildConfigField("boolean", "LLMHOST_PERFORMANCE_BUILD", "false")
            // KleidiAI still follows defaultConfig/LLMHOST_ENABLE_KLEIDIAI (default ON for arm64).
            buildConfigField(
                "String",
                "LLMHOST_RUNTIME_BACKEND",
                "\"${if (kleidiAiEnabled) "CPU-KleidiAI" else "CPU"}\"",
            )
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLLMHOST_DEBUG_HOOKS=ON",
                        "-DLLMHOST_ENABLE_OPENCL=OFF",
                    )
                }
            }
        }
        // release: minified, signed when LLMHOST_RELEASE_* are set, production package id.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("boolean", "LLMHOST_DEBUG_HOOKS", "false")
            buildConfigField("boolean", "LLMHOST_PERFORMANCE_BUILD", "true")
            buildConfigField("String", "LLMHOST_RUNTIME_BACKEND", "\"CPU-KleidiAI\"")
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLLMHOST_DEBUG_HOOKS=OFF",
                        "-DLLMHOST_ENABLE_KLEIDIAI=ON",
                        "-DLLMHOST_ENABLE_OPENCL=OFF",
                    )
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // benchmark: release-like perf (minify + KleidiAI), debug-signed for easy sideload.
        // Package: com.prismai.llmhost.benchmark — installable beside release/debug.
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // Re-apply after initWith so variants stay explicit and greppable.
            buildConfigField("boolean", "LLMHOST_DEBUG_HOOKS", "false")
            buildConfigField("boolean", "LLMHOST_PERFORMANCE_BUILD", "true")
            buildConfigField("String", "LLMHOST_RUNTIME_BACKEND", "\"CPU-KleidiAI\"")
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLLMHOST_DEBUG_HOOKS=OFF",
                        "-DLLMHOST_ENABLE_KLEIDIAI=ON",
                        "-DLLMHOST_ENABLE_OPENCL=OFF",
                    )
                }
            }
        }
        // profile: same as benchmark but profileable for simpleperf / Android Studio profiler.
        create("profile") {
            initWith(getByName("release"))
            applicationIdSuffix = ".profile"
            versionNameSuffix = "-profile"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isProfileable = true
            buildConfigField("boolean", "LLMHOST_DEBUG_HOOKS", "false")
            buildConfigField("boolean", "LLMHOST_PERFORMANCE_BUILD", "true")
            buildConfigField("String", "LLMHOST_RUNTIME_BACKEND", "\"CPU-KleidiAI\"")
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLLMHOST_DEBUG_HOOKS=OFF",
                        "-DLLMHOST_ENABLE_KLEIDIAI=ON",
                        "-DLLMHOST_ENABLE_OPENCL=OFF",
                    )
                }
            }
        }
        // adreno: experimental OpenCL path when LLMHOST_ENABLE_OPENCL + include/lib are set.
        // Falls back to CPU-KleidiAI labeling when OpenCL is not configured.
        create("adreno") {
            initWith(getByName("release"))
            applicationIdSuffix = ".adreno"
            versionNameSuffix = "-adreno"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "LLMHOST_DEBUG_HOOKS", "false")
            buildConfigField("boolean", "LLMHOST_PERFORMANCE_BUILD", "true")
            buildConfigField(
                "String",
                "LLMHOST_RUNTIME_BACKEND",
                "\"${if (openClAvailable) "OpenCL-Adreno" else "CPU-KleidiAI"}\"",
            )
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLLMHOST_DEBUG_HOOKS=OFF",
                        "-DLLMHOST_ENABLE_KLEIDIAI=ON",
                    ) + openClCmakeArgs
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
