import java.text.SimpleDateFormat
import java.util.Date
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    jacoco
    kotlin("jvm") version "1.4.30"
    id("com.github.ben-manes.versions") version "0.36.0"
}

buildscript {
    repositories {
        jcenter()
    }
}

group = "de.fhg.igd"
version = "5.8.1"

val vertxVersion by extra("3.9.4")
val hazelcastVersion by extra("3.12.9")
val prometheusClientVersion by extra("0.9.0")

repositories {
    jcenter()
}

dependencies {
    implementation("org.slf4j:jul-to-slf4j:1.7.30")
    implementation("org.slf4j:log4j-over-slf4j:1.7.30")
    implementation("org.slf4j:slf4j-api:1.7.30")

    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("ch.qos.logback:logback-core:1.2.3")
    implementation("ch.qos.logback.contrib:logback-jackson:0.1.5")
    implementation("ch.qos.logback.contrib:logback-json-classic:0.1.5")
    implementation("org.codehaus.janino:janino:3.1.2") // for conditionals in logback.xml

    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-hazelcast:$vertxVersion")
    implementation("io.vertx:vertx-jdbc-client:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("io.vertx:vertx-mongo-client:$vertxVersion")
    implementation("io.vertx:vertx-shell:$vertxVersion")
    implementation("io.vertx:vertx-web:$vertxVersion")
    implementation("io.vertx:vertx-web-client:$vertxVersion")
    implementation("com.hazelcast:hazelcast:$hazelcastVersion")

    implementation("commons-codec:commons-codec:1.15")
    implementation("commons-io:commons-io:2.8.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.12.0")
    implementation("com.github.zafarkhaja:java-semver:0.9.0")
    implementation("com.google.guava:guava:30.0-jre")
    implementation("com.zaxxer:HikariCP:3.4.5")
    implementation("io.pebbletemplates:pebble:3.1.4")
    implementation("io.projectreactor:reactor-core:3.4.2") // necessary for reactive MongoDB driver
    implementation("io.prometheus:simpleclient:$prometheusClientVersion")
    implementation("io.prometheus:simpleclient_hotspot:$prometheusClientVersion")
    implementation("io.prometheus:simpleclient_vertx:$prometheusClientVersion")
    implementation("org.apache.ant:ant:1.10.9")
    implementation("org.apache.commons:commons-lang3:3.11")
    implementation("org.apache.commons:commons-text:1.9")
    implementation("org.flywaydb:flyway-core:7.3.1")
    implementation("org.mongodb:mongodb-driver-reactivestreams:1.13.1")
    implementation("org.pacesys:openstack4j:3.2.0")
    implementation("org.postgresql:postgresql:42.2.18")
    implementation("org.yaml:snakeyaml:1.27")

    implementation(kotlin("reflect"))
    implementation(kotlin("scripting-jsr223"))
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("script-runtime"))

    testImplementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo:3.0.0")
    testImplementation("io.mockk:mockk:1.10.3")
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("org.assertj:assertj-core:3.18.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.7.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.7.0")
    testImplementation("org.testcontainers:testcontainers:1.15.3")
    testImplementation("org.testcontainers:postgresql:1.15.3")
}

application {
    mainClassName = "MainKt"
}

jacoco {
    toolVersion = "0.8.5"
}

tasks {
    test {
        useJUnitPlatform()
    }

    jacocoTestReport {
        reports {
            xml.isEnabled = true
            html.isEnabled = true
        }
    }

    kotlin {
        target {
            compilations {
                val main by getting

                create("plugins") {
                    dependencies {
                        implementation(main.compileDependencyFiles + main.output.classesDirs)
                    }

                    defaultSourceSet {
                        kotlin.srcDir("$projectDir/conf/plugins")
                    }
                }
            }

            compilations.all {
                kotlinOptions {
                    jvmTarget = "1.8"
                }
            }
        }
    }

    compileKotlin {
        sourceSets {
            main {
                resources {
                    srcDirs("$buildDir/generated-src/main/resources")
                    srcDirs("$projectDir/ui/out")
                }
            }
        }
    }

    withType<Test>().configureEach {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).takeIf { it > 0 } ?: 1
    }

    val generateVersionFile by creating {
        doLast {
            val dst = File(buildDir, "generated-src/main/resources")
            dst.mkdirs()
            val versionFile = File(dst, "version.json")
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            val timestamp = format.format(Date())
            val versionText = """{
              |  "version": "$version",
              |  "build": "${System.getenv("GITHUB_RUN_NUMBER") ?: ""}",
              |  "commit": "${System.getenv("GITHUB_SHA") ?: ""}",
              |  "timestamp": "$timestamp"
              |}""".trimMargin()
            versionFile.writeText(versionText)
        }
    }

    processResources {
        dependsOn(generateVersionFile)
        dependsOn(":ui:processResources")
    }

    // customize start scripts
    startScripts {
        // customize application name
        applicationName = "steep"

        // change current directory to APP_HOME
        doLast {
            val windowsScriptFile = file(getWindowsScript())
            val unixScriptFile = file(getUnixScript())
            windowsScriptFile.writeText(windowsScriptFile.readText()
                .replace("@rem Execute steep".toRegex(), "$0\r\ncd \"%APP_HOME%\""))
            unixScriptFile.writeText(unixScriptFile.readText()
                .replaceFirst("\nexec.+".toRegex(), "\ncd \"\\\$APP_HOME\"$0"))
        }
    }

    val compilePluginsKotlin by getting
    jar {
        dependsOn(compilePluginsKotlin)
    }

    distributions {
        main {
            contents {
                // include 'conf' directory in distribution
                from(projectDir) {
                    include("conf/**/*")
                }
                from("$buildDir/classes/kotlin/plugins") {
                    include("*.class")
                    into("conf/plugins")
                }
            }
        }
    }
}
