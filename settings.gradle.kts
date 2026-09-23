plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "vyra"

include("vyra-core")
include("vyra-redis")
include("vyra-inmemory")
include("vyra-jackson")
include("vyra-gson")
include("examples")