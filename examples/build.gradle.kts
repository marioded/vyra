plugins {
    application
}

dependencies {
    implementation(projects.vyraCore)
    implementation(projects.vyraInmemory)
    implementation(projects.vyraJackson)
}

application {
    mainClass.set("com.marioded.vyra.examples.BroadcastExample")
}

tasks.register<JavaExec>("runRequestResponse") {
    group = "application"
    description = "Runs the point-to-point request/response example"
    mainClass.set("com.marioded.vyra.examples.RequestResponseExample")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runEvents") {
    group = "application"
    description = "Runs the publish/subscribe events example"
    mainClass.set("com.marioded.vyra.examples.EventsExample")
    classpath = sourceSets["main"].runtimeClasspath
}
