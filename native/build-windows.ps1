# Builds airspyhf.dll for the host architecture with MSVC and copies it, with
# the libusb DLL, to dist\native\windows-<arch>.
#
# Usage: native\build-windows.ps1 -Arch x64|ARM64 [-Ref <airspyhf-git-ref>]
#
# The ref may be a branch, a tag or a full commit hash. Windows uses the
# prebuilt libusb binaries published by the libusb project. pthreads4w and the
# C runtime are compiled into airspyhf.dll, so it needs nothing installed.
param(
    [Parameter(Mandatory = $true)][ValidateSet('x64', 'ARM64')][string]$Arch,
    [string]$Ref = ''
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$Root = Split-Path -Parent $PSScriptRoot
$Versions = @{}
Get-Content "$Root\native\versions.env" | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object {
    $Key, $Value = $_ -split '=', 2
    $Versions[$Key] = $Value
}
if (-not $Ref) { $Ref = $Versions.AIRSPYHF_REF }
$LibusbVersion = $Versions.LIBUSB_VERSION

if ($Arch -eq 'x64') {
    $Platform = 'windows-x86_64'
    $UsbDir = 'VS2022\MS64\dll'
} else {
    $Platform = 'windows-aarch64'
    $UsbDir = 'VS2025\ARM64\dll'
}
$Out = "$Root\dist\native\$Platform"
$Licenses = "$Root\dist\native\licenses"
$Work = "$Root\build\native-work"
Remove-Item -Recurse -Force $Work, $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Work, $Out, $Licenses | Out-Null
Set-Location $Work

# Fetching by ref, rather than cloning a branch, also accepts a commit hash.
function Get-Source([string]$Repository, [string]$GitRef, [string]$Directory) {
    git init --quiet $Directory | Out-Null
    git -C $Directory fetch --quiet --depth 1 $Repository $GitRef | Out-Null
    git -c advice.detachedHead=false -C $Directory checkout --quiet FETCH_HEAD | Out-Null
    return (git -C $Directory rev-parse HEAD).Trim()
}

Write-Host "=== libusb $LibusbVersion ==="
$Archive = "libusb-$LibusbVersion.7z"
Invoke-WebRequest "https://github.com/libusb/libusb/releases/download/v$LibusbVersion/$Archive" -OutFile $Archive
$Hash = (Get-FileHash $Archive -Algorithm SHA256).Hash
if ($Hash -ine $Versions.LIBUSB_WINDOWS_7Z_SHA256) {
    throw "libusb archive checksum mismatch: $Hash"
}
7z x $Archive -ousb | Out-Null
$UsbHeader = Get-ChildItem -Recurse -Path usb\include -Filter libusb.h | Select-Object -First 1
if (-not $UsbHeader) { throw 'libusb.h not found in the libusb archive' }

Write-Host "=== pthreads4w ==="
Get-Source $Versions.PTHREADS4W_REPOSITORY $Versions.PTHREADS4W_REF pthreads4w | Out-Null
# context.h misspells __PTW32_PROGCTR in its ARM branch, which stops
# pthread_cancel.c compiling for ARM64. No effect on x64.
$Context = 'pthreads4w\context.h'
(Get-Content -Raw $Context).Replace('#define PTW32_PROGCTR(Context)', '#define __PTW32_PROGCTR(Context)') |
    Set-Content -NoNewline $Context

Write-Host "=== libairspyhf $Ref ==="
$Commit = Get-Source $Versions.AIRSPYHF_REPOSITORY $Ref airspyhf
$Header = 'airspyhf\libairspyhf\src\airspyhf.h'
$Match = Select-String -Path $Header -Pattern '^#define AIRSPYHF_VERSION "(.*)"'
if (-not $Match) { throw "No AIRSPYHF_VERSION in $Header" }
$UpstreamVersion = $Match.Matches[0].Groups[1].Value
if ($Ref -eq $Versions.AIRSPYHF_REF -and $UpstreamVersion -ne $Versions.AIRSPYHF_VERSION) {
    throw "airspyhf.h at $Ref is version $UpstreamVersion, not $($Versions.AIRSPYHF_VERSION)"
}

cmake -S "$Root\native" -B build -A $Arch `
    "-DAIRSPYHF_SOURCE_DIR=$("$Work\airspyhf".Replace('\', '/'))" `
    "-DPTHREADS4W_SOURCE_DIR=$("$Work\pthreads4w".Replace('\', '/'))" `
    "-DLIBUSB_INCLUDE_DIR=$($UsbHeader.DirectoryName.Replace('\', '/'))" `
    "-DLIBUSB_LIBRARY=$("$Work\usb\$UsbDir\libusb-1.0.lib".Replace('\', '/'))"
cmake --build build --config Release --parallel

Write-Host "=== packaging $Platform ==="
$Dll = Get-ChildItem -Recurse -Path build -Filter airspyhf.dll | Select-Object -First 1
if (-not $Dll) { throw 'airspyhf.dll was not produced' }
Copy-Item $Dll.FullName "$Out\airspyhf.dll"
Copy-Item "$Work\usb\$UsbDir\libusb-1.0.dll" "$Out\libusb-1.0.dll"
# The library sources are BSD-3-Clause. Upstream renamed LICENSE to LICENSE.BSD
# when it added the GPL that covers only its build files and tools.
$License = if (Test-Path 'airspyhf\LICENSE.BSD') { 'airspyhf\LICENSE.BSD' } else { 'airspyhf\LICENSE' }
Copy-Item $License "$Licenses\libairspyhf-LICENSE"
Copy-Item 'pthreads4w\LICENSE' "$Licenses\pthreads4w-LICENSE"
Copy-Item 'pthreads4w\NOTICE' "$Licenses\pthreads4w-NOTICE"
@(
    "airspyhf.ref=$Ref",
    "airspyhf.commit=$Commit",
    "airspyhf.version=$UpstreamVersion",
    "libusb.version=$LibusbVersion",
    "pthreads4w.commit=$($Versions.PTHREADS4W_REF)"
) | Set-Content -Encoding ascii "$Out\build-info.properties"
Get-ChildItem $Out
