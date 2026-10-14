plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "tv.own.owntv.mobile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26

        // The staged sources still contain their former application bootstrap and settings/about
        // screen. They are deliberately not registered by this library's manifest, but compiling
        // them keeps the import lossless until the host-facing boundary is extracted in Phase 2.
        buildConfigField("String", "VERSION_NAME", "\"mobile-ui-staging\"")
        buildConfigField("int", "VERSION_CODE", "0")
        buildConfigField("boolean", "DEV_TOOLS", "false")
        buildConfigField("boolean", "DIAGNOSTIC_BUILD", "false")
        buildConfigField("String", "TMDB_EDGE_KEY", "\"\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.keepDebugSymbols += "**/*.so"
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    // Shared ZenPlay data and playback contracts. The module does not start Koin or register any of
    // its module definitions; the universal host remains the single process bootstrap owner.
    implementation(libs.owntv.core)
    implementation(libs.owntv.player.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.graphics.shapes)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.room.runtime)

    implementation(libs.androidx.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
