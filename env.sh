#!/bin/bash
# Local build environment: Temurin JDK 21 + local Maven 3.9.9 (same as rag-app).
export JAVA_HOME="$HOME/tools/jdk-21.0.13+9"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
