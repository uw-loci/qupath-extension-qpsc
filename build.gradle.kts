plugins {
    // Support writing the extension in Groovy (remove this if you don't want to)
    groovy
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
    // Code formatting (like Black for Python)
    id("com.diffplug.spotless") version "7.0.2"
    // Static bug detection
    id("com.github.spotbugs") version "6.1.2"
    // Publish to local Maven for dependent extensions (e.g., qupath-extension-ppm)
    id("maven-publish")
}

// Configure your extension here
qupathExtension {
    name = "qupath-extension-qpsc"
    group = "io.github.uw-loci"
    version = "0.4.2"
    description = "A QuPath extension to allow interaction with a microscope through PycroManager and MicroManager."
    automaticModule = "io.github.uw-loci.extension.qpsc"
}

// Publish to local Maven so dependent extensions (e.g., qupath-extension-ppm) can compile against QPSC
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.uw-loci"
            artifactId = "qupath-extension-qpsc"
            version = "0.4.2"
            from(components["java"])
        }
    }
}

allprojects {
    repositories {
        // tiles-to-pyramid is ONLY available from local Maven (publishToMavenLocal).
        // It is not hosted on any remote repository.
        mavenLocal()
        mavenCentral()
        // SciJava repository - mirrors many OME/Bio-Formats artifacts
        maven {
            name = "SciJava"
            url = uri("https://maven.scijava.org/content/repositories/releases")
            content {
                excludeGroup("io.github.uw-loci")
            }
        }
        // Alternative OME repository URL
        maven {
            name = "OME-Artifacts"
            url = uri("https://artifacts.openmicroscopy.org/artifactory/maven/")
            content {
                excludeGroup("io.github.uw-loci")
            }
        }
        // Original OME repository (may be unreliable)
        maven {
            name = "OME"
            url = uri("https://repo.openmicroscopy.org/artifactory/ome-releases")
            content {
                excludeGroup("io.github.uw-loci")
            }
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
    shadow("io.github.uw-loci:qupath-extension-tiles-to-pyramid:0.2.2")
    // CompileOnly - QuPath provides bioformats at runtime, we just compile against it
    // This avoids trying to resolve OME transitive dependencies during build
    compileOnly("io.github.qupath:qupath-extension-bioformats:0.6.0")
    // LiveViewerWindow.showAcquiredTile uses loci.formats.gui.BufferedImageReader
    // to read 16-bit mono OME-TIFFs that ImageIO can't handle. BufferedImageReader
    // lives in formats-bsd, which is a transitive runtime dep of the BioFormats
    // extension above but not present on the compile classpath. Pull it in as
    // compileOnly so the compiler sees the symbol; at runtime QuPath's bioformats
    // extension supplies it.
    compileOnly("ome:formats-bsd:8.2.0")
    compileOnly("ome:formats-api:8.2.0")
    // If you aren't using Groovy, this can be removed
    shadow(libs.bundles.groovy)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation("io.github.qupath:qupath-app:0.6.0")
    //testImplementation(libs.junit)
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation(libs.bundles.logging)
    testImplementation(libs.qupath.fxtras)
    testImplementation("org.openjfx:javafx-base:$javafxVersion")
    testImplementation("org.openjfx:javafx-graphics:$javafxVersion")
    testImplementation("org.openjfx:javafx-controls:$javafxVersion")
    testImplementation("org.mockito:mockito-core:5.21.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.21.0")
}

//TODO remove before release
//For troubleshooting deprecation warnings,
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}
tasks.test {
    useJUnitPlatform()
    // Move JavaFX JARs from classpath to module path so --add-modules can find them.
    // Temurin JDK (used in CI) does not bundle JavaFX, so the modules are only available
    // as dependency JARs which Gradle places on the classpath by default.
    doFirst {
        val cp = classpath.files
        val fxJars = cp.filter { it.name.startsWith("javafx-") }
        if (fxJars.isNotEmpty()) {
            classpath = files(cp - fxJars)
            jvmArgs(
                "--module-path", fxJars.joinToString(File.pathSeparator),
                "--add-modules", "javafx.base,javafx.graphics,javafx.controls",
                "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Spotless -- auto-formatting
// ---------------------------------------------------------------------------
spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.50.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---------------------------------------------------------------------------
// ASCII-only enforcement (CLAUDE.md policy: no chars > 0x7F in Java sources).
// Prevents the recurring Windows cp1252 encoding failures.
// Runs as part of "check" so it gates the build just like spotlessCheck.
// ---------------------------------------------------------------------------
tasks.register("checkAsciiOnly") {
    description = "Fails if any Java source file contains non-ASCII characters (> 0x7F)"
    group = "verification"
    val srcDirs = fileTree("src") { include("**/*.java") }
    inputs.files(srcDirs)
    doLast {
        val violations = mutableListOf<String>()
        srcDirs.forEach { file ->
            file.readText().lines().forEachIndexed { idx, line ->
                line.forEachIndexed { col, ch ->
                    if (ch.code > 0x7F) {
                        violations.add(
                            "${file.relativeTo(projectDir)}:${idx + 1}:${col + 1}  " +
                            "'$ch' (U+${"04X".format(ch.code)})"
                        )
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Non-ASCII characters found (will break on Windows cp1252):\n" +
                violations.joinToString("\n")
            )
        }
        logger.lifecycle("checkAsciiOnly: all Java sources are ASCII-clean")
    }
}
tasks.named("check") { dependsOn("checkAsciiOnly") }

// ---------------------------------------------------------------------------
// SpotBugs -- static bug detection
// ---------------------------------------------------------------------------
spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
}