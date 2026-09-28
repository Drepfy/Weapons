plugins {
    java
}

group = "dev.drepfy"
version = "1.0.0"

// HikariCP is not shaded: Paper downloads it at startup via the `libraries` list in plugin.yml.
// Keep this in sync with plugin.yml.
val hikariVersion = "6.3.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.24")
    compileOnly("com.zaxxer:HikariCP:$hikariVersion")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.zaxxer:HikariCP:$hikariVersion")
    // Same driver version Paper bundles, so tests exercise the SQL that runs in production.
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

base {
    archivesName = "StaffModeration"
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Java 21 bytecode loads on both 1.21.x (Java 21) and 26.x (Java 25) servers.
        options.release = 21
        options.compilerArgs.add("-Xlint:all,-processing,-serial,-classfile")
    }

    processResources {
        val props = mapOf("version" to project.version, "hikariVersion" to hikariVersion)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
