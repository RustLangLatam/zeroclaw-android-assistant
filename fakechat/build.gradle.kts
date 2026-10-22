plugins {
    id("com.android.application")
}

// A test companion for the notification channel, never shipped: it posts
// messages the way a messaging app does and records the replies it gets.
android {
    namespace = "org.rustlanglatam.zeroclaw.fakechat"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.rustlanglatam.zeroclaw.fakechat"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
