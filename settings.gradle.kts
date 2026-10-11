pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/public")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
    }
}
rootProject.name = "dsh-fusion"
include(":app")
include(":engine")
include(":devicectl")
include(":voice")
include(":overlay")
