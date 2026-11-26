pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.google\\.testing\\.platform(\\..*)?")
            }
        }
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.google\\.testing\\.platform(\\..*)?")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "Trainingsapp"
include(":app")
