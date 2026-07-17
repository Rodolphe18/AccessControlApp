plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

// Pure Kotlin/JVM on purpose: proto-lite generated code has no Android dependency, and this keeps
// the protobuf plugin off AGP's extension — the 0.9.x plugin still casts to the BaseExtension that
// AGP 9 removed, so applying it to an android-library module fails. DataStore lives in :core:data.
kotlin {
    jvmToolchain(11)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            // maybeCreate, not register: on a JVM project the "java" builtin already exists (the
            // java plugin registers it), so registering it again fails. This configures whichever
            // are present and creates the rest.
            task.builtins {
                maybeCreate("java").option("lite")
                maybeCreate("kotlin").option("lite")
            }
        }
    }
}

dependencies {
    implementation(libs.protobuf.kotlin.lite)
}
