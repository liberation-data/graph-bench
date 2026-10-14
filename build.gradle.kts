// One Drivine model and one set of workloads, run against Neo4j, FalkorDB and Oracle (SQL/PGQ).

plugins {
    kotlin("jvm") version "2.3.21"
    id("com.google.devtools.ksp") version "2.3.10"
    application
}

group = "bench"
version = "0.1.0"

val drivineVersion = "0.1.6"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.7"))
    implementation("org.drivine:drivine4j:$drivineVersion")
    ksp("org.drivine:drivine4j-codegen:$drivineVersion")

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.neo4j.driver:neo4j-java-driver:5.28.9")
    implementation("com.falkordb:jfalkordb:0.13.0")
    // jfalkordb is built on Jedis 8; the Spring Boot BOM manages an older one.
    implementation("redis.clients:jedis:8.0.1")
    implementation("com.oracle.database.jdbc:ojdbc11")
    implementation("com.zaxxer:HikariCP")
    runtimeOnly("ch.qos.logback:logback-classic")
}

kotlin {
    compilerOptions {
        // Required for the Drivine DSL, which uses context parameters
        freeCompilerArgs.addAll("-Xcontext-parameters")
    }
}

application {
    mainClass = "bench.MainKt"
    applicationDefaultJvmArgs = listOf("-Xmx3g", "-Xms1g")
}
