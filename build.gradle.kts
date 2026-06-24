plugins {
    java
    id("org.springframework.boot") version "4.1.0-SNAPSHOT"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.back"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/snapshot") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-h2console")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")   // Flyway 자동 설정 추가
    runtimeOnly("org.flywaydb:flyway-database-postgresql")  // PostgreSQL 전용 Flyway 지원을 추가함
    implementation("org.postgresql:postgresql:42.7.10") // PostgreSQL JDBC 드라이버
    implementation("org.springframework.boot:spring-boot-starter-security:4.1.0-M2")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0") // JWT 라이브러리
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2") // Swagger
    implementation("org.springframework.boot:spring-boot-starter-data-redis:4.0.6")    // Redis
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
