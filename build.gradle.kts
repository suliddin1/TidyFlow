plugins {
    java
    application
}

group = "com.tidyflow"
version = "1.0.0"

repositories {
    mavenCentral()
}

val javafxVersion = "21.0.5"
val javafxPlatform = "win"

sourceSets {
    create("tools") {
        java.srcDir("src/tools/java")
    }
}

dependencies {
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-fxml:$javafxVersion:$javafxPlatform")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.named<ProcessResources>("processResources") {
    val appVersion = project.version.toString()
    inputs.property("projectVersion", appVersion)
    filesMatching("version.properties") {
        expand(mapOf("projectVersion" to appVersion))
    }
}

tasks.register<JavaExec>("generateIcons") {
    group = "build"
    description = "Generates PNG app icons (16..256 px) and multi-size icon.ico using Java2D."
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("com.tidyflow.tools.IconGeneratorMain")
    args(project.projectDir.absolutePath)
}

application {
    mainClass.set("com.tidyflow.app.Main")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
