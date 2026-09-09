import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    // TODO: kapt deprecated, migrate to KSP (com.google.devtools.ksp) when Kotlin >=2.0 + Room/Hilt KSP support verified.
    // Kept as kapt for now to not break build with Kotlin 1.9.22 (current libs.versions.toml) — KSP would require
    // plugin + compiler id changes and testing on N97 low-resource build. No functional impact, only build speed.
    kotlin("kapt")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply { if (versionPropsFile.exists()) load(FileInputStream(versionPropsFile)) }
var generatedVersionCode = (versionProps["VERSION_CODE"] as? String)?.toIntOrNull() ?: 3
var generatedVersionName = versionProps["VERSION_NAME"] as? String ?: "1.2.3"
// Si es una build de assemble, incrementa y guarda
if (gradle.startParameter.taskNames.any { it.contains("assemble", ignoreCase = true) }) {
    generatedVersionCode += 1
    val parts = generatedVersionName.split(".")
    val patch = (parts.lastOrNull()?.toIntOrNull() ?: 2) + 1
    generatedVersionName = "1.2.$patch"
    versionProps["VERSION_CODE"] = generatedVersionCode.toString()
    versionProps["VERSION_NAME"] = generatedVersionName
    versionProps.store(versionPropsFile.outputStream(), "Auto-incremented on assemble")
}

fun getAutoVersionCode(): Int = generatedVersionCode
fun getAutoVersionName(): String = generatedVersionName

android {
    namespace = "com.tgmon.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tgmon.app"
        minSdk = 26
        targetSdk = 35
        versionCode = generatedVersionCode
        versionName = generatedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        resValue("string", "app_name", "TGMon")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "TGMon Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            resValue("string", "app_name", "TGMon")
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "TGMon-${versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material)
    implementation("androidx.compose.material:material-icons-extended")
    
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)
}
