plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.openchatai.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openchatai.app"
        minSdk = 26
        // targetSdk 28 SENGAJA (standar Termux/UserLAnd): Android 10+ memblokir
        // execve() binary di app data untuk targetSdk >= 29 — rootfs Linux
        // (apt/node/python) dieksekusi proot dari app data. compileSdk tetap 34.
        targetSdk = 28
        versionCode = 11
        versionName = "1.9.0"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            // 64-bit saja agar build CI cepat; perangkat modern sudah arm64.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // APK release ditandatangani dengan debug key agar langsung bisa dipasang.
            // Ganti dengan keystore produksi Anda sendiri sebelum distribusi publik.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST")
        // jniLibs: libproot.so (proot statis prebuilt) JANGAN di-strip AGP —
        // strip bisa merusak ELF statis + debug info dipakai symbol lookup.
        jniLibs.keepDebugSymbols += "**/libproot.so"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Compose (BOM mengatur versi seluruh artefak Compose)
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Kotlinx
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Jaringan
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Preferensi + penyimpanan aman
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Ekstraksi tar.gz rootfs Linux (Apache Commons Compress)
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Markdown rendering di chat
    implementation("io.noties.markwon:core:4.6.2")
}
