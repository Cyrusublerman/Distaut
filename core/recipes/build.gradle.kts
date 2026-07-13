plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:model"))
}

kotlin {
    jvmToolchain(17)
}
