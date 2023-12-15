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

  api("net.sf.jopt-simple:jopt-simple:4.5")
  api("org.ow2.asm:asm-commons:${asmVersion}")
  api("org.ow2.asm:asm-tree:${asmVersion}")
  api("org.ow2.asm:asm-util:${asmVersion}")
  api("org.ow2.asm:asm-analysis:${asmVersion}")
  compileOnly("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
  api("org.apache.logging.log4j:log4j-core:2.0-beta9-fixed")
  api("org.apache.logging.log4j:log4j-api:2.0-beta9-fixed")
}

val gitVersion: groovy.lang.Closure<String> by extra

version = gitVersion()

buildConfig {
  buildConfigField("String", "VERSION", provider { "\"${project.version}\"" })
  className("BuildConfig")
  packageName("com.gtnewhorizons.retrofuturabootstrap")
  useJavaOutput()
}

// Apply a specific Java toolchain to ease working on different environments.
java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
    vendor.set(JvmVendorSpec.AZUL)
  }
  withSourcesJar()
  withJavadocJar()
}

tasks.withType<JavaCompile>() {
  options.encoding = "UTF-8"
  options.release = 17
}

tasks.withType<Javadoc>().configureEach {
  this.javadocTool.set(
      javaToolchains.javadocToolFor {
        languageVersion.set(JavaLanguageVersion.of(17))
        vendor.set(JvmVendorSpec.AZUL)
      })
  with(options as StandardJavadocDocletOptions) {
    links("https://docs.oracle.com/en/java/javase/17/docs/api/")
    addStringOption("Xdoclint:all,-missing", "-quiet")
  }
}

tasks.named<Test>("test") { useJUnitPlatform() }

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
