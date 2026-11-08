// One Drivine model and one set of workloads, run against Neo4j, FalkorDB, Oracle (SQL/PGQ) and Postgres.

plugins {
    kotlin("jvm") version "2.3.21"
    id("com.google.devtools.ksp") version "2.3.10"
    application
}

group = "bench"
version = "0.1.0"

// The Drivine to build against: this one, or another to compare with it, as `-PdrivineVersion=0.2.0` (run.sh: DRIVINE_VERSION).
// The search workloads need 0.5.0 or later, where Oracle and Postgres score full-text search by BM25 as Neo4j's Lucene does.
val drivineVersion = (findProperty("drivineVersion") as String?) ?: "0.6.1"

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
    implementation("org.postgresql:postgresql")
    implementation("com.zaxxer:HikariCP")
    runtimeOnly("ch.qos.logback:logback-classic")
}

// Drivine's KSP names the module in .drivine/model.json after the folder it is built in, unless told: a checkout or a
// run's copy in a folder of another name would rewrite the committed file, and run.sh would then call the harness dirty.
ksp {
    arg("drivine.model.module", rootProject.name)
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
