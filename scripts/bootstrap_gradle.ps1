if (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle wrapper --gradle-version 9.6.0
    Write-Host "Gradle wrapper generated."
} else {
    Write-Error "No system Gradle found. Open the project in Android Studio Quail 4 or later, or install Gradle 9.6 and rerun this script."
}
