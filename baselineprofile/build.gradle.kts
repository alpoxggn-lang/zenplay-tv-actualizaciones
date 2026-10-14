plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "tv.own.owntv.baselineprofile"
    compileSdk {
        version = release(37)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Same reason as :app — androidx.benchmark's prebuilt .so are already stripped, so the strip
        // step only ever printed "Unable to strip …" without changing a byte.
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }

    defaultConfig {
        // Macrobenchmark needs API 28+ to read the compilation state it drives.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        missingDimensionStrategy("compatibility", "normal")
    }

    // :app has Normal and Legacy compatibility flavors. Profiles are recorded against Normal and
    // merged into main, so the same code-path profile ships in both APKs. Use a connected ARM64
    // API 33+ device, or pass -Pzenplay.devX86=true for an x86_64 emulator.
    targetProjectPath = ":app"
}

// Run on a single connected device/emulator. Set `useConnectedDevices = false` and add a managed
// device here if this ever needs to run unattended in CI.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
