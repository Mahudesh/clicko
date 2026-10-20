import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing key lives outside the repo (~/.clicko). Every update MUST be signed with it,
// or phones refuse to install the update over the old version. Back it up!
val releaseKey = Properties().apply {
    val f = file("${System.getProperty("user.home")}/.clicko/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mahudesh.tvremote"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mahudesh.tvremote"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "1.0.3"
        // Where in-app updates come from (GitHub Releases).
        buildConfigField("String", "UPDATE_REPO", "\"Mahudesh/clicko\"")
    }

    signingConfigs {
        if (releaseKey.isNotEmpty()) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
    compileSdkMinor = 0
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.datastore.preferences)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.lottie.compose)
    implementation(libs.lucide)
    implementation(libs.shadcn)
    implementation(libs.kadb)
    implementation(libs.bouncycastle.pkix)
}
