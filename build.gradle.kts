plugins {
// This correctly declares that the plugin is available for modules to use,
    // without applying it to the root project itself.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false

    // Add the Google Services plugin here as well, using its ID
    id("com.google.gms.google-services") version "4.4.4" apply false}
