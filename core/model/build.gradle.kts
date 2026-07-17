plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM on purpose: no Android dependency means the domain model stays
// testable on the JVM and can be shared with the Ktor server if we ever want to.
kotlin {
    jvmToolchain(11)
}
