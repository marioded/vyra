plugins {
    `java-library`
}

dependencies {
    api(projects.vyraCore)
    api(libs.jackson.databind)
    api(libs.jackson.dataformat.smile)
    api(libs.jackson.dataformat.cbor)
}