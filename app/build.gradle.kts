plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore/release.properties")
val keystoreProperties = Properties()
val hasLocalKeystoreProperties = keystorePropertiesFile.exists()

if (hasLocalKeystoreProperties) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun projectSecret(name: String): String? {
    return (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }
}

android {
    namespace = "cn.tryxd.powermanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.tryxd.powermanager"
        minSdk = 26
        targetSdk = 33
        versionCode = 7
        versionName = "1.3.3"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = projectSecret("PM_RELEASE_STORE_FILE")
            val storePassword = projectSecret("PM_RELEASE_STORE_PASSWORD")
            val keyAlias = projectSecret("PM_RELEASE_KEY_ALIAS")
            val keyPassword = projectSecret("PM_RELEASE_KEY_PASSWORD")

            if (
                !storeFilePath.isNullOrBlank() &&
                !storePassword.isNullOrBlank() &&
                !keyAlias.isNullOrBlank() &&
                !keyPassword.isNullOrBlank()
            ) {
                storeFile = rootProject.file(storeFilePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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

tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Verify release signing config and version code for upgrade-safe publishing."

    doLast {
        val requiredKeys = listOf(
            "PM_RELEASE_STORE_FILE",
            "PM_RELEASE_STORE_PASSWORD",
            "PM_RELEASE_KEY_ALIAS",
            "PM_RELEASE_KEY_PASSWORD"
        )
        val missingKeys = requiredKeys.filter { projectSecret(it).isNullOrBlank() }
        check(missingKeys.isEmpty()) {
            "Missing release signing values: ${missingKeys.joinToString(", ")}. " +
                "Set them in environment variables, Gradle properties, or keystore/release.properties."
        }

        val releaseStore = rootProject.file(projectSecret("PM_RELEASE_STORE_FILE")!!)
        check(releaseStore.exists()) {
            "Release keystore file does not exist: ${releaseStore.path}"
        }

        val releaseVersionCode = android.defaultConfig.versionCode ?: 0
        check(releaseVersionCode > 0) {
            "versionCode must be greater than 0 for upgrade-safe publishing."
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn("verifyReleaseSigning")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
