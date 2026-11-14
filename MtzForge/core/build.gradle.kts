plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application { mainClass.set("dev.mtzforge.core.CliKt") }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // Heap pequeño a propósito: los tests de MTZ enormes deben pasar en streaming.
    maxHeapSize = "96m"
    // Los MTZ generados por los tests quedan aquí para inspección manual.
    systemProperty("mtzforge.samples", rootProject.file("samples").absolutePath)
}
