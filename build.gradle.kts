import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    `maven-publish`
    application
    id("io.github.goooler.shadow") version "8.1.2"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.javacord:javacord:3.8.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.3.3")
    implementation("org.apache.commons:commons-text:1.11.0")
    implementation("org.apache.logging.log4j:log4j-api:2.22.1")
    implementation("org.apache.logging.log4j:log4j-core:2.22.1")
}

group = "com.github.drakepork"
version = "2.5"
description = "TaskBot"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.github.drakepork.taskbot.MainBot")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<ShadowJar> {
    relocate("org.mariadb.jdbc", "com.github.drakepork.taskbot.mariadb")
}
