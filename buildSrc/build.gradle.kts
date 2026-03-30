plugins {
    `java-library`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("io.sigpipe:jbsdiff:1.0")
}
