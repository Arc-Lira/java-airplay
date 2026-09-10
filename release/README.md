# Release Output

Run the following command from the repository root on Windows with JDK 25:

```bat
gradlew.bat release
```

Generated files:

```text
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip.sha256
release/java-airplay-<version>.<yyMMdd>-windows-arm64.zip
release/java-airplay-<version>.<yyMMdd>-windows-arm64.zip.sha256
```

Each ZIP contains the executable JAR, a compact Java 25 runtime for that architecture, the matching GStreamer runtime, startup scripts, editable configuration, bilingual documentation, and licenses. End users extract the ZIP that matches their PC and run `start.bat`; they do not need to install Java or GStreamer.

The large ZIP and checksum files are build artifacts and are excluded from Git.
