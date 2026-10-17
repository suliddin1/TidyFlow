plugins {
    java
    application
}

group = "com.tidyflow"
version = "0.1.0"

repositories {
    mavenCentral()
}

val javafxVersion = "21.0.5"
val javafxPlatform = "win"

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

application {
    mainClass.set("com.tidyflow.app.Main")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
