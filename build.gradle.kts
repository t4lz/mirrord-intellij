import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Paths
import java.util.EnumSet

fun properties(key: String) = project.findProperty(key).toString()

plugins {
    // Java support
    id("java")
    // Kotlin support
    id("org.jetbrains.kotlin.jvm") version "1.8.22"
    // Gradle IntelliJ Plugin
    id("org.jetbrains.intellij") version "1.+"
    // Gradle Changelog Plugin
    id("org.jetbrains.changelog") version "2.+"

    id("org.jlleitschuh.gradle.ktlint") version "11.5.0"
}

group = properties("pluginGroup")
version = properties("pluginVersion")

// Configure project's dependencies
repositories {
    mavenCentral()
    maven {
        url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }

    maven {
        url = uri("https://packages.jetbrains.team/maven/p/iuia/qa-automation-maven")
    }
}

val remoteRobotVersion = "0.11.19"

dependencies {
    implementation(project(":mirrord-products-idea"))
    implementation(project(":mirrord-products-pycharm"))
    implementation(project(":mirrord-products-rubymine"))
    implementation(project(":mirrord-products-goland"))
    implementation(project(":mirrord-products-nodejs"))
    implementation(project(":mirrord-products-rider"))
    testImplementation("com.intellij.remoterobot:remote-robot:$remoteRobotVersion")
    testImplementation("com.intellij.remoterobot:remote-fixtures:$remoteRobotVersion")
    testImplementation("com.intellij.remoterobot:ide-launcher:0.11.19.414")
    testImplementation("com.automation-remarks:video-recorder-junit5:2.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.9.3")
    testImplementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

// Configure Gradle IntelliJ Plugin - read more: https://github.com/JetBrains/gradle-intellij-plugin
intellij {
    pluginName.set(properties("pluginName"))
    version.set(properties("platformVersion"))
    // So we can have run configurations with different IDEs to test (GO/PC) etc
    val platformType = System.getenv("PLATFORMTYPE")
    if (platformType != null) {
        type.set(platformType)
    }

    // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file.
    if (platformType != "PY" && platformType != "PC" && platformType != "GO" && platformType != "RD") {
        plugins.set(
            properties("platformPlugins")
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
        )
    }

    updateSinceUntilBuild.set(false)
}

allprojects {
    // Configure project's dependencies
    repositories {
        mavenCentral()
    }

    properties("javaVersion").let {
        tasks.withType<JavaCompile> {
            sourceCompatibility = it
            targetCompatibility = it
        }

        tasks.withType<KotlinCompile> {
            kotlinOptions {
                jvmTarget = it
            }
        }
    }
}

gradle.taskGraph.whenReady(
    closureOf<TaskExecutionGraph> {
        val ignoreSubprojectTasks = listOf(
            "buildSearchableOptions",
            "listProductsReleases",
            "patchPluginXml",
            "publishPlugin",
            "runIde",
            "runPluginVerifier",
            "verifyPlugin",
            "runIdeForUiTests"
        )

        // Don't run some tasks for subprojects
        for (task in allTasks) {
            if (task.project != task.project.rootProject) {
                when (task.name) {
                    in ignoreSubprojectTasks -> task.enabled = false
                }
            }
        }
    }
)

tasks {
    // Removing this makes build stop working, not sure why.
    buildSearchableOptions {
        enabled = false
    }
    // Set the JVM compatibility versions

    properties("javaVersion").let {
        withType<JavaCompile> {
            sourceCompatibility = it
            targetCompatibility = it
        }
        withType<KotlinCompile> {
            kotlinOptions.jvmTarget = it
        }
    }

    wrapper {
        gradleVersion = properties("gradleVersion")
    }

    changelog {
        version.set(properties("pluginVersion"))
        groups.set(listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security", "Internal"))
    }

    patchPluginXml {
        version.set(properties("pluginVersion"))

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription.set(
            projectDir.resolve("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end))
            }.joinToString("\n").run { markdownToHTML(this) }
        )
        if (!System.getenv("CI_BUILD_PLUGIN").toBoolean()) {
            changeNotes.set(
                provider {
                    changelog.renderItem(
                        changelog.run {
                            getOrNull(properties("pluginVersion")) ?: getLatest()
                        },
                        Changelog.OutputType.HTML
                    )
                }
            )
        }
    }

    prepareSandbox {
        // binaries to copy from $projectDir/bin to $pluginDir/bin with same path.
        // we have custom delve until delve 20 is widely used
        val binaries = listOf("macos/arm64/dlv", "macos/x86-64/dlv")
        binaries.forEach {
                binary ->
            from(file(project.projectDir.resolve("bin").resolve(binary))) {
                // into treats last part as directory, so need to drop it.
                into(Paths.get(pluginName.get(), "bin", binary).parent.toString())
            }
        }
    }

    runIde {
        environment("PLUGIN_TESTING_ENVIRONMENT", "true")
    }

    // Configure UI tests plugin
    // Read more: https://github.com/JetBrains/intellij-ui-test-robot
    runIdeForUiTests {
        systemProperty("robot-server.port", "8082")
        systemProperty("robot-server.host.public", "true")
        systemProperty("ide.mac.message.dialogs.as.sheets", "false")
        systemProperty("jb.privacy.policy.text", "<!--999.999-->")
        systemProperty("jb.consents.confirmation.enabled", "false")
        systemProperty("idea.trust.all.projects", "true")
        systemProperty("ide.show.tips.on.startup.default.value", "false")
    }

    downloadRobotServerPlugin {
        version.set(remoteRobotVersion)
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
        // pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels.set(listOf("beta"))
        channels.set(listOf(properties("pluginVersion").split('-').getOrElse(1) { "default" }.split('.').first()))
    }

    runPluginVerifier {
        ideVersions.set(listOf("IU-232.5150.116", "IU-222.4554.10"))
        failureLevel.set(EnumSet.of(FailureLevel.COMPATIBILITY_PROBLEMS, FailureLevel.INVALID_PLUGIN))
    }

    test {
        useJUnitPlatform()
        systemProperty("test.workspace", projectDir.resolve("test-workspace").absolutePath)
        val pluginFileName = properties("pluginName") + "-" + properties("pluginVersion") + ".zip"
        systemProperty("test.plugin.path", projectDir.resolve("build/distributions/$pluginFileName").absolutePath)
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
    }
}
