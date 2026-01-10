plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
    maven { url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") }
}

val remoteRobotVersion = "0.11.23"

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        zipSigner()
    }

    // Temporal SDK for server connectivity
    implementation("io.temporal:temporal-sdk:1.27.0")

    // Temporal Testing for workflow replay
    implementation("io.temporal:temporal-testing:1.27.0")

    // UI Testing with Remote-Robot
    testImplementation("com.intellij.remoterobot:remote-robot:$remoteRobotVersion")
    testImplementation("com.intellij.remoterobot:remote-fixtures:$remoteRobotVersion")
    // OkHttp required for RemoteRobot constructor (compile-time dependency)
    testImplementation("com.squareup.okhttp3:okhttp:3.14.9")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "241"
            untilBuild = "251.*"
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.10"
    }

    test {
        useJUnitPlatform()
        // Exclude UI tests from regular test run - they need IDE running
        exclude("**/ui/**")
    }
}

// Create a dedicated uiTest task that runs tests requiring the IDE
val uiTest by tasks.registering(Test::class) {
    description = "Run UI tests that require the IDE to be running with Robot Server"
    group = "verification"

    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    // Only include UI tests
    include("**/ui/**")

    // Environment info for tests
    systemProperty("robot.server.port", "8082")

    // Give tests more time to interact with IDE
    systemProperty("junit.jupiter.execution.timeout.default", "60s")

    // JDK17 module access required for GSON/Retrofit in RemoteRobot
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

// Register runIdeForUiTests task for Remote-Robot UI testing
val runIdeForUiTests by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Drobot-server.port=8082",
                "-Dide.mac.message.dialogs.as.sheets=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Djb.consents.confirmation.enabled=false",
            )
        }
        // Open a test project automatically
        args = listOf(projectDir.resolve("testProject").absolutePath)
    }
    plugins {
        robotServerPlugin()
    }
}
