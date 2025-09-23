dependencies {
    implementation(project(":dnq-entity-store"))
    implementation(project(":dnq-utils"))
    api(project(":dnq-xodus-open-api"))
    implementation(libs.bouncyCastle)
    testImplementation(project(":dnq-utils", "testArtifacts"))
    testImplementation(project(":dnq-xodus-open-api", "testArtifacts"))
}