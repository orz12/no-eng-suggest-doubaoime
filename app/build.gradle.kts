import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(propertyName: String, environmentName: String): String? {
    return System.getenv(environmentName)?.takeIf(String::isNotBlank)
        ?: releaseSigningProperties.getProperty(propertyName)?.takeIf(String::isNotBlank)
}

val releaseStoreFile = releaseSigningValue(
    "storeFile",
    "DOUBAO_SIGNING_STORE_FILE"
)
val releaseStorePassword = releaseSigningValue(
    "storePassword",
    "DOUBAO_SIGNING_STORE_PASSWORD"
)
val releaseKeyAlias = releaseSigningValue(
    "keyAlias",
    "DOUBAO_SIGNING_KEY_ALIAS"
)
val releaseKeyPassword = releaseSigningValue(
    "keyPassword",
    "DOUBAO_SIGNING_KEY_PASSWORD"
)
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)
val hasAnyReleaseSigningValue = releaseSigningValues.any { it != null }
val hasReleaseSigning = releaseSigningValues.all { it != null }

if (hasAnyReleaseSigningValue && !hasReleaseSigning) {
    throw GradleException(
        "Release 签名配置不完整。请补齐 keystore.properties 或 DOUBAO_SIGNING_* 环境变量。"
    )
}

android {
    namespace = "com.doubao.ime.noensuggest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.doubao.ime.noensuggest"
        minSdk = 26
        targetSdk = 35
        versionCode = 66
        versionName = "0.9.27-hook-tags"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = true
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
        jniLibs {
            pickFirsts += listOf("**/libshadowhook.so")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation("io.github.libxposed:service:101.0.0")
    implementation(libs.shadowhook)
}
