// The JetBrains VS Code language server currently leaks its IntelliJ-only file
// system provider into Gradle Tooling API builds. Kotlin/Native's commonizer
// runs in a separate JVM without that provider, so remove only that known IDE
// value before project import tasks are configured.
val defaultFileSystemProviderProperty = "java.nio.file.spi.DefaultFileSystemProvider"
val intellijFileSystemProvider =
    "com.intellij.platform.core.nio.fs.MultiRoutingFileSystemProvider"

if (System.getProperty(defaultFileSystemProviderProperty) == intellijFileSystemProvider) {
    System.clearProperty(defaultFileSystemProviderProperty)
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenFlightMobile"

include(":androidApp")
include(":shared")
