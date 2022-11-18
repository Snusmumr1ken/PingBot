plugins {
    id("java")
    id("application")
}

application {
    mainClass.set("pingbot.Main")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

group = "org.pingbot"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation ("com.github.pengrad:java-telegram-bot-api:6.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}
