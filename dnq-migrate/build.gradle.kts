dependencies {
    implementation(project(":dnq-entity-store"))
    implementation(project(":dnq-query"))
    testImplementation(project(":dnq-utils"))

    // original xodus dependencies needed only for the process of data migration from Xodus to YouTrackDB
    implementation("org.jetbrains.xodus:xodus-entity-store:3.1-dev-shaded")
    implementation("org.jetbrains.xodus:xodus-environment:3.1-dev-shaded")
    implementation("org.jetbrains.xodus:xodus-utils:3.1-dev-shaded")
    implementation("org.jetbrains.xodus:xodus-openAPI:3.1-dev-shaded")

    implementation("commons-io:commons-io:2.15.1")

    testImplementation(project(":dnq-utils", "testArtifacts"))
    testImplementation(project(":dnq-query", "testArtifacts"))
    testImplementation(project(":dnq-entity-store", "testArtifacts"))
    testImplementation(kotlin("test"))
}