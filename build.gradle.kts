plugins {
  `java-library`
  id("com.palantir.git-version") version "3.0.0"
  `maven-publish`
  id("com.diffplug.spotless") version "6.23.3"
  id("com.github.gmazzo.buildconfig") version "4.2.0"
}

group = "com.gtnewhorizons.retrofuturabootstrap"

repositories {
  maven { url = uri("https://libraries.minecraft.net/") }
  maven {
    url = uri("https://files.prismlauncher.org/maven")
    metadataSources { artifact() }
  }
  mavenCentral()
}

spotless {
  encoding("UTF-8")
  java {
    target("src/**/*.java")
    toggleOffOn()
    importOrder()
    removeUnusedImports()
    palantirJavaFormat("2.39.0")
  }
  kotlinGradle {
    toggleOffOn()
    ktfmt("0.39")
    trimTrailingWhitespace()
    indentWithSpaces(4)
    endWithNewline()
  }
}

val asmVersion = "9.6"

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")

  compileOnly("org.jetbrains:annotations:24.1.0")
  api("net.sf.jopt-simple:jopt-simple:4.5")
  api("org.ow2.asm:asm-commons:${asmVersion}")
  api("org.ow2.asm:asm-tree:${asmVersion}")
  api("org.ow2.asm:asm-util:${asmVersion}")
  api("org.ow2.asm:asm-analysis:${asmVersion}")
  api("org.ow2.asm:asm-deprecated:7.1")
  compileOnly("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
  api("org.apache.logging.log4j:log4j-core:2.0-beta9-fixed")
  api("org.apache.logging.log4j:log4j-api:2.0-beta9-fixed")
}

val gitVersion: groovy.lang.Closure<String> by extra
val envVersion: String? = System.getenv("VERSION")

version = envVersion ?: gitVersion()

buildConfig {
  buildConfigField("String", "VERSION", provider { "\"${project.version}\"" })
  className("BuildConfig")
  packageName("com.gtnewhorizons.retrofuturabootstrap")
  useJavaOutput()
}

lateinit var java9: SourceSet

// Apply a specific Java toolchain to ease working on different environments.
java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
    vendor.set(JvmVendorSpec.AZUL)
  }
  withSourcesJar()
  withJavadocJar()

  sourceSets {
    // Stub classes, not actually included in the jar
    main {}
    java9 =
        create("java9") {
          compileClasspath +=
              this@sourceSets.main.get().output + files(configurations.compileClasspath)
        }
    test {
      runtimeClasspath = files(this@test.output, tasks.jar, configurations.testRuntimeClasspath)
    }
  }
}

tasks.withType<JavaCompile>() {
  options.encoding = "UTF-8"
  options.release = 8
}

tasks.named<JavaCompile>(java9.compileJavaTaskName) { options.release = 9 }

tasks.jar {
  into("META-INF/versions/9") { from(java9.output) }
  manifest.attributes["Multi-Release"] = "true"
  manifest.attributes["Specification-Title"] = "launchwrapper"
  manifest.attributes["Specification-Version"] = "1.12"
  manifest.attributes["Specification-Vendor"] = "Minecraft"
  manifest.attributes["Implementation-Title"] = "RetroFuturaBootstrap"
  manifest.attributes["Implementation-Version"] = project.version.toString()
  manifest.attributes["Implementation-Vendor"] = "GTNewHorizons"
}

tasks.processResources {
  inputs.property("version", project.version.toString())
  filesMatching("**/*.properties") { expand("version" to project.version.toString()) }
}

tasks.withType<Javadoc>().configureEach {
  this.javadocTool.set(
      javaToolchains.javadocToolFor {
        languageVersion.set(JavaLanguageVersion.of(17))
        vendor.set(JvmVendorSpec.AZUL)
      })
  with(options as StandardJavadocDocletOptions) {
    links("https://docs.oracle.com/en/java/javase/21/docs/api/")
    addStringOption("Xdoclint:all,-missing", "-quiet")
  }
}

tasks.named<Test>("test") { useJUnitPlatform() }

val test8 =
    tasks.register<Test>("test8") {
      group = "Verification tasks"
      description = "Run the test suite on Java 8."

      useJUnitPlatform()
      this.classpath = sourceSets.test.get().runtimeClasspath
      this.testClassesDirs = sourceSets.test.get().output.classesDirs
      this.javaLauncher =
          javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(8)
            vendor = JvmVendorSpec.AZUL
          }
    }

tasks.check { dependsOn(test8) }

publishing {
  publications { create<MavenPublication>("rfbMaven") { from(components["java"]) } }

  repositories {
    maven {
      url = uri("http://jenkins.usrv.eu:8081/nexus/content/repositories/releases")
      isAllowInsecureProtocol = true
      credentials {
        username = System.getenv("MAVEN_USER") ?: "NONE"
        password = System.getenv("MAVEN_PASSWORD") ?: "NONE"
      }
    }
  }
}
