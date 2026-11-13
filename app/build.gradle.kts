plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersion = providers.gradleProperty("multitasker.version").get()
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "multitasker.version parts above 99 do not fit the version code" }
    major * 10_000 + minor * 100 + patch
}

android {
    namespace = "wtf.buddyworks.multitasker"
    compileSdk = 37

    defaultConfig {
        applicationId = "wtf.buddyworks.multitasker"
        minSdk = 34
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
}
