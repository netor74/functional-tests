plugins {
    id("java")
}

group = "io.rubuy74"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.testcontainers:testcontainers:2.0.1")
    testImplementation("org.testcontainers:junit-jupiter:1.19.8")
    testImplementation("org.testcontainers:kafka:1.19.8")
    testImplementation("org.testcontainers:postgresql:1.19.8")
    testImplementation("io.rest-assured:rest-assured:5.4.0")
    testImplementation("org.awaitility:awaitility:4.2.1")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    testImplementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.test {
    useJUnitPlatform()
}