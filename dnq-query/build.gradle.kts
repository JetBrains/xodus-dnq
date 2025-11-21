dependencies {
    implementation(project(":dnq-entity-store"))
    implementation(project(":dnq-utils"))
    api(project(":dnq-xodus-open-api"))
    implementation("com.github.penemue:keap:0.3.0")

    implementation("commons-io:commons-io:2.15.1")
    implementation(libs.slf4j.simple)

    testImplementation(project(":dnq-utils", "testArtifacts"))
    testImplementation(project(":dnq-entity-store", "testArtifacts"))
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(kotlin("test"))
}
val testArtifacts by configurations.creating

configurations {
    testArtifacts.extendsFrom(testRuntimeOnly.get())
}

tasks {
    val jarTest by creating(Jar::class) {
        archiveClassifier.set("test")
        from(sourceSets.test.get().output)
    }
    artifacts {
        add("testArtifacts", jarTest)
    }

    register<JavaExec>("migrateXodusToOrient") {
        group = "application"
        mainClass = "jetbrains.exodus.query.metadata.MigrateXodusToOrientKt"
        classpath = sourceSets["main"].runtimeClasspath
        jvmArgs = listOf(
            "-server",
            "-Xmx16g",
            "-XX:+HeapDumpOnOutOfMemoryError",
        )
        systemProperties = mapOf(
            "xodusDatabaseDirectory" to (project.findProperty("xodusDatabaseDirectory")),
            "xodusStoreName" to (project.findProperty("xodusStoreName")),
            "xodusCipherKey" to (project.findProperty("xodusCipherKey")),
            "xodusCipherIV" to (project.findProperty("xodusCipherIV")),
            "xodusMemoryUsagePercentage" to (project.findProperty("xodusMemoryUsagePercentage")),

            "orientDatabaseType" to (project.findProperty("orientDatabaseType")),
            "orientDatabaseDirectory" to (project.findProperty("orientDatabaseDirectory")),
            "orientDatabaseName" to (project.findProperty("orientDatabaseName")),
            "orientUsername" to (project.findProperty("orientUsername")),
            "orientPassword" to (project.findProperty("orientPassword")),

            "validateDataAfterMigration" to (project.findProperty("validateDataAfterMigration")),
            "entitiesPerTransaction" to (project.findProperty("entitiesPerTransaction")),
        )
    }
}
