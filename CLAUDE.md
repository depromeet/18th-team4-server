# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**readwith** — a Spring Boot 4.0.5 web application using Java 25, Gradle 9.4.1, and Lombok.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run the application
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.readwith.SomeTest"

# Run a single test method
./gradlew test --tests "com.readwith.SomeTest.methodName"

# Clean build
./gradlew clean build
```

## Architecture

- **Base package**: `com.readwith` — Spring Boot auto-scans from here
- **Framework**: Spring Boot 4.0.5 with `spring-boot-starter-webmvc` (servlet-based web)
- **Build**: Gradle with `io.spring.dependency-management` plugin for BOM-managed dependencies
- **Java version**: 25 (toolchain-enforced)
- **Lombok**: Available in both main and test source sets (compileOnly + annotationProcessor)
- **Testing**: JUnit 5 via `junit-platform-launcher`; Spring test support via `spring-boot-starter-webmvc-test`
