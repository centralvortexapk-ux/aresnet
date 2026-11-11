$wc = New-Object System.Net.WebClient
$wc.DownloadFile('https://repo1.maven.org/maven2/org/gradle/gradle-wrapper/9.3.1/gradle-wrapper-9.3.1.jar', 'C:\Users\Equipo\AndroidStudioProjects\aresnet\gradle\wrapper\gradle-wrapper.jar')
Write-Host "Downloaded gradle-wrapper.jar"