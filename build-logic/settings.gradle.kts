// 约定插件是独立构建：自带 AGP / KGP 的编译依赖，版本仍读根目录的 version catalog。
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
