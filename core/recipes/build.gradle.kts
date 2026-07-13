plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}
