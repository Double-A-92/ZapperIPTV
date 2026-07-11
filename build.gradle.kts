plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set("1.5.0")
    android.set(true)
    outputToConsole.set(true)
}
