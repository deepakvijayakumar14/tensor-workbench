plugins {
    // Lets Gradle download the Java 21 toolchain when the local JDK is different.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tensor-workbench-api"
