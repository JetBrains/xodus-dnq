dependencies {
    api(project(":dnq-xodus-open-api"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation(project(":dnq-utils"))
    implementation(libs.lz4)
}