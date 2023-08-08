import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    `maven-publish`
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.javacord:javacord:3.8.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.1.4")
}

group = "com.github.drakepork"
version = "2.0"
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