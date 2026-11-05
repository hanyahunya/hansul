import java.util.Properties
import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun configuredValue(name: String, defaultValue: String): String =
    providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: defaultValue

val apiBaseUrl = configuredValue("HANSUL_API_BASE_URL", "http://10.0.2.2:8080/")
    .let { if (it.endsWith('/')) it else "$it/" }
val oidcIssuer = configuredValue(
    "HANSUL_OIDC_ISSUER",
    "http://10.0.2.2:8180/realms/hansul",
).removeSuffix("/")
val oidcClientId = configuredValue("HANSUL_OIDC_CLIENT_ID", "hansul-android")
val oidcRedirectUri = configuredValue(
    "HANSUL_OIDC_REDIRECT_URI",
    "com.hansul.app:/oauth2redirect",
)
val oidcRedirectScheme = URI(oidcRedirectUri).scheme
    ?: error("HANSUL_OIDC_REDIRECT_URI must contain a URI scheme")

android {
    namespace = "com.hansul.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hansul.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "OIDC_ISSUER", "\"${oidcIssuer.replace("\"", "\\\"")}\"")
        buildConfigField("String", "OIDC_CLIENT_ID", "\"${oidcClientId.replace("\"", "\\\"")}\"")
        buildConfigField("String", "OIDC_REDIRECT_URI", "\"${oidcRedirectUri.replace("\"", "\\\"")}\"")
        manifestPlaceholders["appAuthRedirectScheme"] = oidcRedirectScheme
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.core)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.appauth)
    implementation(libs.litert.lm)
    implementation(libs.jsoup)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    debugImplementation(libs.androidx.compose.ui.tooling)
}
