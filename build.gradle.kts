plugins {
    val indraVer = "3.2.0"
    id("net.kyori.indra") version indraVer
    id("net.kyori.indra.publishing") version indraVer
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.org.slf4j.slf4j.api)
    implementation(libs.it.unimi.dsi.fastutil)
    implementation(libs.net.java.dev.jna)
    implementation(libs.at.yawk.lz4.lz4.java)
    implementation(libs.com.github.luben.zstd.jni)
    implementation(libs.org.yaml.snakeyaml)

    testImplementation(libs.org.junit.jupiter.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    manifest {
        attributes("FMLModType" to "GAMELIBRARY")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()

    maxHeapSize = "1G"

    testLogging {
        events("passed")
    }
}

indra {
    javaVersions {
        target(21)
    }
    publishSnapshotsTo("paperSnapshots", "https://repo.papermc.io/repository/maven-snapshots/")
    publishReleasesTo("paperReleases", "https://repo.papermc.io/repository/maven-releases/")
    gpl3OnlyLicense()
    github("Tuinity", "LeafPile")
    configurePublications {
        pom {
            developers {
                developer {
                    id = "spottedleaf"
                }
            }
        }
    }
    signWithKeyFromProperties("signingKey", "signingPassword")
}
