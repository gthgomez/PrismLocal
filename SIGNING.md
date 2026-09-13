# Release Signing

Release signing is intentionally external to this artifact. Do not place keystores,
passwords, or private signing material in the project tree.

`assembleRelease` builds an unsigned release APK unless all of these Gradle
properties or environment variables are set:

- `LLMHOST_RELEASE_STORE_FILE`
- `LLMHOST_RELEASE_STORE_PASSWORD`
- `LLMHOST_RELEASE_KEY_ALIAS`
- `LLMHOST_RELEASE_KEY_PASSWORD`

Example local invocation:

```powershell
$env:LLMHOST_RELEASE_STORE_FILE='<keystore-dir>\release.jks'
$env:LLMHOST_RELEASE_STORE_PASSWORD='...'
$env:LLMHOST_RELEASE_KEY_ALIAS='llmhost'
$env:LLMHOST_RELEASE_KEY_PASSWORD='...'
cd <workspace>\Project_Android\PrismLocal
.\gradlew.bat --no-daemon assembleRelease
```

User-level `~/.gradle/gradle.properties` may define the same keys. After the
folder rename, `LLMHOST_RELEASE_STORE_FILE` must use `PrismLocal`, not the
legacy `LLMHostAndroid` path.

CI should provide the same values through secret storage and materialize the
keystore outside the repository checkout.
