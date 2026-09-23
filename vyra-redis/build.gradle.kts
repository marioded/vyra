plugins {
    `java-library`
}

dependencies {
    api(projects.vyraCore)
    api(libs.lettuce.core)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(projects.vyraJackson)
}