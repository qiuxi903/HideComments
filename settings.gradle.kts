pluginManagement {
    repositories {
        // 阿里云镜像放前面：Maven Central 偶尔会 TLS 握手失败，构建会莫名其妙挂掉
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "HideComments"

include(":core")
include(":hook")
include(":app")
