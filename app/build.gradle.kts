plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val supportedAbis = setOf("arm64-v8a", "x86_64")
val optimizedAbi = providers.gradleProperty("appAbi").getOrElse("arm64-v8a")
require(optimizedAbi == "universal" || optimizedAbi in supportedAbis) { "Unsupported appAbi: $optimizedAbi" }

android {
    namespace = "dev.neurostenografist"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.neurostenografist"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/nemotron/CMakeLists.txt") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".ultra"
            ndk { abiFilters += supportedAbis }
        }
        release {
            ndk { abiFilters += supportedAbis }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("optimized") {
            initWith(getByName("release"))
            ndk {
                abiFilters.clear()
                abiFilters += if (optimizedAbi == "universal") supportedAbis else setOf(optimizedAbi)
            }
            // Same package/certificate as the installed test app: preserve recordings and weights.
            applicationIdSuffix = ".ultra"
            versionNameSuffix = "-ultra-optimized"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
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
    }
    androidResources {
        // Weights are downloaded into private storage, never shipped in the APK.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:CVS:thumbs.db:picasa.ini:!*~:models"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // The app uses the sherpa JNI bridge, which links ONNX Runtime directly.
        jniLibs.excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }
    lint {
        // Baseline only records GradleDependency version notices. The two API
        // issues (MainActivity dynamicLightColorScheme, RecordingService mic FGS
        // type) were fixed with real SDK_INT guards, NOT suppressed here - do
        // not re-baseline new findings of those kinds; new findings fail the build.
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.k2fsa.sherpa:onnx:2.0.1-nnapi2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // JVM-compatible org.json so TranscriptJson unit tests run on the host.
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    // Avoid removed InputManager.getInstance reflection on Android 17.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
