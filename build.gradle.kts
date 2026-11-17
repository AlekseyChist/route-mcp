plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

group = "routemcp"
version = "0.1.0"

application {
    mainClass.set("routemcp.MainKt")
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(libs.mcp.kotlin.server)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // slf4j-simple пишет логи в stderr — это важно: stdout занят протоколом MCP
    implementation(libs.slf4j.simple)
    runtimeOnly(libs.kotlin.logging)
}

// Один "толстый" jar со всеми зависимостями: build/libs/route-mcp.jar
tasks.shadowJar {
    archiveFileName.set("route-mcp.jar")
    mergeServiceFiles()
}
