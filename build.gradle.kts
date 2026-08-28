plugins {
    `java-library`
    `maven-publish`
    id("com.gradleup.shadow") version libs.versions.shadow
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    mavenCentral()
}

dependencies {
    implementation(libs.eclipse.jgit)
    implementation(libs.commons.io)
    implementation(libs.json)
    implementation(libs.commons.lang3)
    compileOnly(libs.paper.api)
}

group = "com.lemonlightmc"
version = "2.3.0"
description = "A CI/CD plugin for Minecraft Servers and Networks"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.jar {
    archiveClassifier.set("thin")
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["shadow"])
    }
}

tasks.register("printVersion") {
    doLast {
        println(version)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
}
