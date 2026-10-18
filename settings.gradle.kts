pluginManagement {
    // 约定插件在 build-logic 里预编译，这里把它的插件 id 接进来（AGP/KGP 版本仍由 catalog 管）。
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "adsh"
include(":app")
include(":seam")
include(":platform:termux")
include(":ui:blocks")
include(":feature:chat")
include(":core:tools")
include(":core:data")
include(":core:agent")
