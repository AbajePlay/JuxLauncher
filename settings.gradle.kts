rootProject.name = "JuxLauncher"

pluginManagement {
    repositories {
        mavenCentral()
        maven("https://repo1.maven.org/maven2")
        maven("https://repo.huaweicloud.com/repository/maven")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo1.maven.org/maven2")
        maven("https://repo.huaweicloud.com/repository/maven")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
    }
}
