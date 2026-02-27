plugins {
    // Support writing the extension in Groovy (remove this if you don't want to)
    groovy
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

// Configure your extension here
qupathExtension {
    name = "qupath-extension-qpsc"
    group = "io.github.michaelsnelson"
    version = "0.3.0"
    description = "A QuPath extension to allow interaction with a microscope through PycroManager and MicroManager."
    automaticModule = "io.github.michaelsnelson.extension.qpsc"
}


allprojects {
    repositories {
        mavenLocal() // Checks your local Maven repository first.
        mavenCentral()
        // SciJava repository - mirrors many OME/Bio-Formats artifacts
        maven {
            name = "SciJava"
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
        // Alternative OME repository URL
        maven {
            name = "OME-Artifacts"
            url = uri("https://artifacts.openmicroscopy.org/artifactory/maven/")
        }
        // Original OME repository (may be unreliable)
        maven {
            name = "OME"
            url = uri("https://repo.openmicroscopy.org/artifactory/ome-releases")
        }
    }
}
val javafxVersion = "17.0.2"
// TODO: Define your dependencies here
dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    //shadow(libs.slf4j)
    shadow(libs.snakeyaml)
    shadow(libs.gson)

    // Bundle tiles-to-pyramid into the shadow JAR for single-file distribution
    shadow("io.github.uw-loci:qupath-extension-tiles-to-pyramid:0.1.0")
    // CompileOnly - QuPath provides bioformats at runtime, we just compile against it
    // This avoids trying to resolve OME transitive dependencies during build
    compileOnly("io.github.qupath:qupath-extension-bioformats:0.7.0-rc1")
    // If you aren't using Groovy, this can be removed
    shadow(libs.bundles.groovy)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation("io.github.qupath:qupath-app:0.7.0-rc1")
    //testImplementation(libs.junit)
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation(libs.bundles.logging)
    testImplementation(libs.qupath.fxtras)
    testImplementation("org.openjfx:javafx-base:$javafxVersion")
    testImplementation("org.openjfx:javafx-graphics:$javafxVersion")
    testImplementation("org.openjfx:javafx-controls:$javafxVersion")
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.2.0")
}

//TODO remove before release
//For troubleshooting deprecation warnings,
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}
tasks.test {
    useJUnitPlatform()
    jvmArgs = listOf(
        "--add-modules", "javafx.base,javafx.graphics,javafx.controls",
        "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED"
    )
}