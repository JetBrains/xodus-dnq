dependencies {
    implementation(project(":dnq-entity-store"))
    implementation(project(":dnq-utils"))
    api(project(":dnq-xodus-open-api"))
    implementation("com.github.penemue:keap:0.3.0")

    api("org.jetbrains.xodus:xodus-entity-store:3.1-dev-shaded")
    api("org.jetbrains.xodus:xodus-environment:3.1-dev-shaded")
    api("org.jetbrains.xodus:xodus-utils:3.1-dev-shaded")
    api("org.jetbrains.xodus:xodus-openAPI:3.1-dev-shaded")

    implementation("commons-io:commons-io:2.15.1")
    implementation(libs.slf4j.simple)

    testImplementation(project(":dnq-utils", "testArtifacts"))
    testImplementation(project(":dnq-entity-store", "testArtifacts"))
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(kotlin("test"))
}

tasks {
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
