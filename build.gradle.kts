import org.gradle.internal.jvm.Jvm
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

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
val tidyflowUpgradeUuid = providers.gradleProperty("tidyflowUpgradeUuid").get()

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

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
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

// --- Windows Packaging Pipeline (jdeps -> jlink -> jpackage -> ZIP + EXE + SHA256SUMS + self-check) ---

data class PackagingJdk(val home: File, val featureVersion: Int)

fun resolvePackagingJdk(): PackagingJdk {
    try {
        val launcher = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get()
        val home = launcher.metadata.installationPath.asFile
        val ver = launcher.metadata.languageVersion.asInt()
        logger.lifecycle("[packaging] Using JDK 21 toolchain at: ${home.absolutePath}")
        return PackagingJdk(home, ver)
    } catch (_: Exception) {
        val currentHome = Jvm.current().javaHome
        val currentVer = JavaVersion.current().majorVersion.toInt()
        logger.lifecycle(
            "[packaging] JDK 21 toolchain not installed locally; packaging with current JDK $currentVer at: ${currentHome.absolutePath}"
        )
        return PackagingJdk(currentHome, currentVer)
    }
}

fun jdkTool(jdkHome: File, toolName: String): String {
    val exe = File(jdkHome, "bin/$toolName.exe")
    return if (exe.exists()) exe.absolutePath else File(jdkHome, "bin/$toolName").absolutePath
}

fun runExternalCommand(
    command: List<String>,
    extraPathDir: File? = null
): String {
    val pb = ProcessBuilder(command)
    if (extraPathDir != null) {
        val pathKey = pb.environment().keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "Path"
        val existingPath = pb.environment()[pathKey] ?: ""
        pb.environment()[pathKey] = "${extraPathDir.absolutePath}${File.pathSeparator}$existingPath"
    }
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val process = pb.start()
    val outThread = Thread { process.inputStream.copyTo(stdout) }
    val errThread = Thread { process.errorStream.copyTo(stderr) }
    outThread.start()
    errThread.start()
    val exitCode = process.waitFor()
    outThread.join()
    errThread.join()

    val outText = stdout.toString(Charsets.UTF_8).trim()
    val errText = stderr.toString(Charsets.UTF_8).trim()
    if (exitCode != 0) {
        throw GradleException(
            "Command failed with exit code $exitCode: ${command.joinToString(" ")}\nSTDOUT:\n$outText\nSTDERR:\n$errText"
        )
    }
    return outText
}

fun findWixBinDirectory(jdkFeatureVersion: Int): File? {
    val pathEnv = System.getenv("PATH") ?: System.getenv("Path") ?: ""
    val pathDirs = pathEnv.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) }

    val standardDirs = mutableListOf<File>()
    listOf("C:\\Program Files (x86)", "C:\\Program Files").forEach { base ->
        val root = File(base)
        if (root.isDirectory) {
            root.listFiles { f -> f.isDirectory && f.name.startsWith("WiX Toolset", ignoreCase = true) }
                ?.sortedByDescending { it.name }
                ?.forEach { wixDir ->
                    val bin = File(wixDir, "bin")
                    if (bin.isDirectory) {
                        standardDirs.add(bin)
                    }
                }
        }
    }

    val candidateDirs = pathDirs + standardDirs
    for (dir in candidateDirs) {
        val hasWix3 = File(dir, "candle.exe").isFile && File(dir, "light.exe").isFile
        val hasWix4Plus = jdkFeatureVersion >= 24 && File(dir, "wix.exe").isFile
        if (hasWix3 || hasWix4Plus) {
            return dir
        }
    }
    return null
}

val packagingInputDir = layout.buildDirectory.dir("packaging/input")
val packagingRuntimeDir = layout.buildDirectory.dir("packaging/runtime")
val packagingAppImageDir = layout.buildDirectory.dir("packaging/app-image")
val installerOutputDir = layout.buildDirectory.dir("installer")

val preparePackageInput = tasks.register<Sync>("preparePackageInput") {
    group = "distribution"
    description = "Collects the application JAR and runtime dependencies for jpackage."
    dependsOn(tasks.named("jar"))
    from(tasks.named("jar"))
    from(configurations.runtimeClasspath)
    into(packagingInputDir)
}

val createRuntimeImage = tasks.register("createRuntimeImage") {
    group = "distribution"
    description = "Determines required JDK modules with jdeps and builds a custom runtime image with jlink."
    dependsOn(preparePackageInput)

    val inputDirProvider = packagingInputDir
    val runtimeDirProvider = packagingRuntimeDir

    doLast {
        val jdk = resolvePackagingJdk()
        val inputDir = inputDirProvider.get().asFile
        val runtimeDir = runtimeDirProvider.get().asFile

        val jarPaths = inputDir.listFiles { f -> f.isFile && f.extension.equals("jar", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.map { it.absolutePath }
            ?: throw GradleException("No JARs found in ${inputDir.absolutePath}")

        val jdepsCmd = mutableListOf(
            jdkTool(jdk.home, "jdeps"),
            "-q",
            "--multi-release", "21",
            "--ignore-missing-deps",
            "--print-module-deps"
        )
        jdepsCmd.addAll(jarPaths)

        val detectedCsv = runExternalCommand(jdepsCmd)
        val baselineModules = linkedSetOf(
            "java.base",
            "java.desktop",
            "java.sql",
            "java.logging",
            "java.xml",
            "java.scripting",
            "jdk.unsupported"
        )
        detectedCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { baselineModules.add(it) }

        val moduleList = baselineModules.joinToString(",")
        logger.lifecycle("[packaging] Linking custom runtime image with modules: $moduleList")

        if (runtimeDir.exists()) {
            runtimeDir.deleteRecursively()
        }
        runtimeDir.parentFile.mkdirs()

        runExternalCommand(
            listOf(
                jdkTool(jdk.home, "jlink"),
                "--no-header-files",
                "--no-man-pages",
                "--strip-debug",
                "--compress", "zip-6",
                "--add-modules", moduleList,
                "--output", runtimeDir.absolutePath
            )
        )
    }
}

val packageAppImage = tasks.register("packageAppImage") {
    group = "distribution"
    description = "Builds the portable Windows application image with jpackage."
    dependsOn(preparePackageInput, createRuntimeImage)

    val inputDirProvider = packagingInputDir
    val runtimeDirProvider = packagingRuntimeDir
    val appImageDirProvider = packagingAppImageDir
    val appVersion = project.version.toString()
    val mainJarName = "tidyflow-$appVersion.jar"
    val iconFile = layout.projectDirectory.file("packaging/windows/icon.ico").asFile

    doLast {
        val jdk = resolvePackagingJdk()
        val appImageParent = appImageDirProvider.get().asFile
        if (appImageParent.exists()) {
            appImageParent.deleteRecursively()
        }
        appImageParent.mkdirs()

        runExternalCommand(
            listOf(
                jdkTool(jdk.home, "jpackage"),
                "--type", "app-image",
                "--dest", appImageParent.absolutePath,
                "--name", "TidyFlow",
                "--app-version", appVersion,
                "--vendor", "TidyFlow",
                "--description", "Rule-based folder organizer and duplicate finder for Windows",
                "--icon", iconFile.absolutePath,
                "--input", inputDirProvider.get().asFile.absolutePath,
                "--main-jar", mainJarName,
                "--main-class", "com.tidyflow.app.Main",
                "--runtime-image", runtimeDirProvider.get().asFile.absolutePath,
                "--java-options", "-Dfile.encoding=UTF-8",
                "--java-options", "--enable-native-access=ALL-UNNAMED"
            )
        )
    }
}

val packagePortableZip = tasks.register<Zip>("packagePortableZip") {
    group = "distribution"
    description = "Packages the jpackage app-image into build/installer/TidyFlow-<version>-portable.zip."
    dependsOn(packageAppImage)

    val appVersion = project.version.toString()
    archiveFileName.set("TidyFlow-$appVersion-portable.zip")
    destinationDirectory.set(installerOutputDir)
    from(packagingAppImageDir)
}

val packageInstallerExe = tasks.register("packageInstallerExe") {
    group = "distribution"
    description = "Builds build/installer/TidyFlow-<version>.exe with jpackage when WiX Toolset is installed."
    dependsOn(preparePackageInput, createRuntimeImage)

    val inputDirProvider = packagingInputDir
    val runtimeDirProvider = packagingRuntimeDir
    val outDirProvider = installerOutputDir
    val appVersion = project.version.toString()
    val mainJarName = "tidyflow-$appVersion.jar"
    val iconFile = layout.projectDirectory.file("packaging/windows/icon.ico").asFile
    val upgradeUuid = tidyflowUpgradeUuid
    val requireWix = providers.gradleProperty("requireWix").map { it.equals("true", ignoreCase = true) }.getOrElse(false)

    doLast {
        val jdk = resolvePackagingJdk()
        val outDir = outDirProvider.get().asFile
        outDir.mkdirs()

        val wixBin = findWixBinDirectory(jdk.featureVersion)
        if (wixBin == null) {
            val msg = """
                [packaging] WiX Toolset was not found on this machine; skipping Windows .exe installer generation.
                [packaging] Portable ZIP and SHA256SUMS.txt will still be produced.
                [packaging] To install WiX Toolset v3 (required by JDK 21 jpackage), run in an Administrator PowerShell:
                            choco install wixtoolset -y
            """.trimIndent()
            if (requireWix) {
                throw GradleException(msg)
            }
            logger.warn(msg)
            return@doLast
        }

        logger.lifecycle("[packaging] Found WiX Toolset at: ${wixBin.absolutePath}")
        val targetExe = File(outDir, "TidyFlow-$appVersion.exe")
        if (targetExe.exists()) {
            targetExe.delete()
        }

        runExternalCommand(
            listOf(
                jdkTool(jdk.home, "jpackage"),
                "--type", "exe",
                "--dest", outDir.absolutePath,
                "--name", "TidyFlow",
                "--app-version", appVersion,
                "--vendor", "TidyFlow",
                "--description", "Rule-based folder organizer and duplicate finder for Windows",
                "--icon", iconFile.absolutePath,
                "--input", inputDirProvider.get().asFile.absolutePath,
                "--main-jar", mainJarName,
                "--main-class", "com.tidyflow.app.Main",
                "--runtime-image", runtimeDirProvider.get().asFile.absolutePath,
                "--java-options", "-Dfile.encoding=UTF-8",
                "--java-options", "--enable-native-access=ALL-UNNAMED",
                "--win-per-user-install",
                "--win-menu",
                "--win-shortcut",
                "--win-dir-chooser",
                "--win-upgrade-uuid", upgradeUuid
            ),
            extraPathDir = wixBin
        )
    }
}

val generateChecksums = tasks.register("generateChecksums") {
    group = "distribution"
    description = "Writes build/installer/SHA256SUMS.txt for the packaged artifacts."
    dependsOn(packagePortableZip, packageInstallerExe)

    val outDirProvider = installerOutputDir
    val appVersion = project.version.toString()

    doLast {
        val outDir = outDirProvider.get().asFile
        outDir.mkdirs()

        val candidateNames = listOf(
            "TidyFlow-$appVersion.exe",
            "TidyFlow-$appVersion-portable.zip"
        )
        val lines = mutableListOf<String>()
        for (name in candidateNames) {
            val file = File(outDir, name)
            if (file.isFile) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                lines.add("$hex  ${file.name}")
            }
        }

        if (lines.isEmpty()) {
            throw GradleException("No artifacts found in ${outDir.absolutePath} to checksum")
        }

        val sumsFile = File(outDir, "SHA256SUMS.txt")
        sumsFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        logger.lifecycle("[packaging] Wrote ${sumsFile.absolutePath}:\n${lines.joinToString("\n")}")
    }
}

val verifyPackage = tasks.register("verifyPackage") {
    group = "distribution"
    description = "Runs scripts/verify-package.ps1 against build/installer artifacts and verifies --self-check."
    dependsOn(generateChecksums)

    val scriptFile = layout.projectDirectory.file("scripts/verify-package.ps1").asFile
    val outDirProvider = installerOutputDir
    val appVersion = project.version.toString()
    val requireWix = providers.gradleProperty("requireWix").map { it.equals("true", ignoreCase = true) }.getOrElse(false)

    doLast {
        val cmd = mutableListOf(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", scriptFile.absolutePath,
            "-InstallerDir", outDirProvider.get().asFile.absolutePath,
            "-Version", appVersion
        )
        if (requireWix) {
            cmd.add("-RequireInstaller")
        }
        val output = runExternalCommand(cmd)
        logger.lifecycle(output)
    }
}

tasks.register("packageRelease") {
    group = "distribution"
    description = "Builds the custom jlink runtime, portable ZIP, Windows EXE installer (if WiX is present), SHA256SUMS.txt, and verifies --self-check."
    dependsOn(packagePortableZip, packageInstallerExe, generateChecksums, verifyPackage)
}
