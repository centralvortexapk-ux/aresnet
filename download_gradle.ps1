$wc = New-Object System.Net.WebClient
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$wc.DownloadFile('https://services.gradle.org/distributions/gradle-9.3.1-bin.zip', 'C:\Users\Equipo\AndroidStudioProjects\aresnet\gradle-9.3.1-bin.zip')
Write-Host "Download complete"