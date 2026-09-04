param(
    [string]$Version = "3.3.0",
    [string]$JpackagePath
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

if ([string]::IsNullOrWhiteSpace($JpackagePath)) {
    $command = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        $JpackagePath = $command.Source
    }
}

if ([string]::IsNullOrWhiteSpace($JpackagePath) -or -not (Test-Path -LiteralPath $JpackagePath)) {
    throw "jpackage.exe não foi encontrado. Informe o caminho com -JpackagePath, por exemplo: 'C:\Program Files\Java\jdk-21\bin\jpackage.exe'."
}

if ($null -eq (Get-Command candle.exe -ErrorAction SilentlyContinue) -or
    $null -eq (Get-Command light.exe -ErrorAction SilentlyContinue)) {
    throw "O WiX Toolset 3 não está disponível no PATH. Instale-o e abra um novo PowerShell antes de empacotar."
}

mvn clean package
mvn dependency:copy-dependencies "-DoutputDirectory=target\libs"

$destination = Join-Path $projectRoot ("dist-installer-" + $Version)
New-Item -ItemType Directory -Path $destination -Force | Out-Null

& $JpackagePath `
    --type exe `
    --name "Royal Server Access" `
    --app-version $Version `
    --vendor "Royal Max" `
    --description "Gerenciador de acesso ao servidor Samba" `
    --input target `
    --main-jar samba-manager-0.1.0.jar `
    --main-class br.com.suaempresa.sambamanager.SambaManagerApp `
    --module-path target\libs `
    --add-modules javafx.controls,javafx.graphics,javafx.base,java.net.http `
    --win-menu `
    --win-menu-group "Royal Max" `
    --win-shortcut `
    --win-dir-chooser `
    --win-per-user-install `
    --dest $destination

if ($LASTEXITCODE -ne 0) {
    throw "Não foi possível gerar o instalador."
}

Write-Host "Instalador criado em: $destination"
