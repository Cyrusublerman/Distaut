plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":core:model"))
    implementation(project(":core:effects"))
    implementation(project(":core:render-api"))
}

kotlin {
    jvmToolchain(17)
}
