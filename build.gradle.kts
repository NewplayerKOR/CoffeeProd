plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.back"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(27)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-h2console")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")   // Flyway 자동 설정 추가
    runtimeOnly("org.flywaydb:flyway-database-postgresql")  // PostgreSQL 전용 Flyway 지원을 추가함
    implementation("org.postgresql:postgresql") // PostgreSQL JDBC 드라이버
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0") // JWT 라이브러리
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0") // Swagger
    implementation("org.springframework.boot:spring-boot-starter-data-redis")    // Redis
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// DEV8 harness is deliberately absent from test, bootJar and the publishing workflow.
val performance = sourceSets.create("performance") {
    java.srcDir("src/test/performance/java")
    resources.srcDir("src/test/performance/resources")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[performance.implementationConfigurationName].extendsFrom(configurations.implementation.get(), configurations.testImplementation.get())
configurations[performance.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get(), configurations.testRuntimeOnly.get())

tasks.register<Test>("perfTest") {
    description = "DEV8 harness checks; -PperfServer runs explicitly owned PostgreSQL scenarios"
    testClassesDirs = performance.output.classesDirs
    classpath = performance.runtimeClasspath
    useJUnitPlatform {
        if (!providers.gradleProperty("perfServer").isPresent) excludeTags("server")
        else includeTags("server")
    }
    providers.gradleProperty("perfCandidate").orNull?.let {
        require(it.matches(Regex("[1-8]"))) { "perfCandidate must be 1..8" }
        filter { includeTestsMatching("*.PerfServerTest.case${it}*") }
    }
    maxParallelForks = 1
}
tasks.register<org.springframework.boot.gradle.tasks.bundling.BootJar>("perfBootJar") {
    description = "Disposable measurement application and HTTP mock PG; never a release artifact"
    archiveClassifier.set("perf")
    targetJavaVersion.set(JavaVersion.VERSION_27)
    mainClass.set("com.back.coffeeprod.performance.PerfApplication")
    classpath(performance.runtimeClasspath)
}
