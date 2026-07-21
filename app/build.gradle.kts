import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val posConfigFile = file("src/main/assets/pos-config.txt")
val posBuildConfig = posConfigFile.readLines()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
    .associate { line ->
        val separator = line.indexOf('=')
        line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }
val configuredAppName = posBuildConfig["APP_NAME"].orEmpty().ifBlank { "Windu Kopi by Akas" }
val configuredLogoResource = posBuildConfig["LAUNCHER_LOGO"].orEmpty()
    .ifBlank { "drawable/windu_kopi_logo.png" }
    .substringBeforeLast('.')
    .let { "@$it" }
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.akas.pos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.akas.pos"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.5"

        buildConfigField("String", "POS_URL", "\"https://windu-kopi.akas.my.id/\"")
        resValue("string", "app_name", configuredAppName)
        manifestPlaceholders["launcherIcon"] = configuredLogoResource
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    signingConfigs {
        create("release") {
            check(keystorePropertiesFile.exists()) {
                "keystore.properties tidak ditemukan. Release APK tidak dapat ditandatangani."
            }
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "$configuredAppName.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
