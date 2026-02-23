# ---------------------------------------------------------------------------
# run.ps1 - Compile and run Java classes against local CoreNLP JARs
#
# Usage:
#   .\run.ps1                          # sentiment test with built-in sample
#   .\run.ps1 ..\input\mydoc.txt       # sentiment test on plain-text file
#   .\run.ps1 ..\input\mydoc.docx      # sentiment test on Word document
#   .\run.ps1 -Parse ..\input\file.txt # speaker turn parsing only (no CoreNLP)
#   .\run.ps1 -Parse ..\input\file.docx
#   .\run.ps1 -Resolve ..\input\file.txt  # target resolution (who speaks to whom)
#   .\run.ps1 -Resolve ..\input\file.docx
# ---------------------------------------------------------------------------

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Helper: extract plain text from a .docx file.
# A .docx is a ZIP archive; the body text lives in word/document.xml.
# This requires no external tools — only .NET ZIP + XML, always available.
# ---------------------------------------------------------------------------
function Extract-TextFromDocx {
    param([string]$DocxPath)

    $tmpDir = Join-Path $env:TEMP ("corenlp_docx_" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmpDir | Out-Null

    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [System.IO.Compression.ZipFile]::ExtractToDirectory($DocxPath, $tmpDir)

        $xmlPath = Join-Path $tmpDir "word\document.xml"
        if (-not (Test-Path $xmlPath)) {
            throw "word/document.xml not found inside $DocxPath — is it a valid .docx?"
        }

        [xml]$xml = Get-Content $xmlPath -Encoding UTF8

        # Each <w:p> is a paragraph; collect text from all <w:t> nodes inside it.
        $ns = @{ w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main" }
        $paragraphs = Select-Xml -Xml $xml -XPath "//w:p" -Namespace $ns

        $lines = foreach ($para in $paragraphs) {
            $texts = Select-Xml -Xml $para.Node -XPath ".//w:t" -Namespace $ns |
                     ForEach-Object { $_.Node.InnerText }
            $line = $texts -join ""
            if ($line.Trim() -ne "") { $line }
        }

        return $lines -join "`n"
    }
    finally {
        Remove-Item -Recurse -Force $tmpDir -ErrorAction SilentlyContinue
    }
}

# -- Paths ------------------------------------------------------------------
$scriptDir = $PSScriptRoot
$libDir    = Join-Path $scriptDir "..\lib\stanford-corenlp\stanford-corenlp-4.5.10"
$outDir    = Join-Path $scriptDir "out"

# -- Build classpath from all JARs in the lib dir --------------------------
$jars = Get-ChildItem -Path $libDir -Filter "*.jar" |
        Where-Object { $_.Name -notmatch "-sources|-javadoc" } |
        Select-Object -ExpandProperty FullName

if (-not $jars) {
    Write-Error "No JARs found in $libDir"
    exit 1
}

# On Windows, classpath entries are separated by semicolons
$cp = ($jars -join ";") + ";$outDir"

Write-Host ""
Write-Host "Using $($jars.Count) JARs from: $libDir"
Write-Host ""

# -- Create output directory ------------------------------------------------
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

# -- Compile ----------------------------------------------------------------
Write-Host "[1/2] Compiling Java sources..."
$srcFiles = Get-ChildItem -Path $scriptDir -Filter "*.java" | Select-Object -ExpandProperty FullName

$javacArgs = @(
    "-encoding", "UTF-8",
    "-source",   "17",
    "-target",   "17",
    "-cp",       $cp,
    "-d",        $outDir
) + $srcFiles

& javac @javacArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Compilation failed."
    exit 1
}
Write-Host "    Compilation succeeded -> $outDir"
Write-Host ""

# -- Resolve input file (handle .docx) ------------------------------------
$inputArg   = $null
$tmpTxtFile = $null
$parseMode  = $false

# Check for mode flags
$fileArgs = @()
$resolveMode = $false
foreach ($a in $args) {
    if ($a -eq "-Parse")   { $parseMode = $true }
    elseif ($a -eq "-Resolve") { $resolveMode = $true }
    else { $fileArgs += $a }
}

if ($fileArgs.Count -gt 0) {
    $inputFile = Resolve-Path $fileArgs[0] | Select-Object -ExpandProperty Path

    if ($inputFile -match "\.docx$") {
        Write-Host "Extracting text from: $inputFile"
        $text = Extract-TextFromDocx -DocxPath $inputFile

        $tmpTxtFile = Join-Path $env:TEMP ("corenlp_input_" + [guid]::NewGuid().ToString("N") + ".txt")
        [System.IO.File]::WriteAllText($tmpTxtFile, $text, [System.Text.UTF8Encoding]::new($false))
        Write-Host "Extracted $($text.Length) characters -> temp file"
        Write-Host ""
        $inputArg = $tmpTxtFile
    } else {
        $inputArg = $inputFile
    }
}

# -- Run --------------------------------------------------------------------
$mainClass = if ($resolveMode) { "TargetResolver" } elseif ($parseMode) { "SpeakerTurnParser" } else { "SentimentTest" }
Write-Host "[2/2] Running $mainClass..."
Write-Host ""

$javaArgs = @(
    "-Xmx4g",    # CoreNLP models need ~2-3 GB; 4 GB is safe
    "-cp", $cp,
    $mainClass
)

if ($inputArg) { $javaArgs += $inputArg }

try {
    & java @javaArgs
} finally {
    if ($tmpTxtFile -and (Test-Path $tmpTxtFile)) {
        Remove-Item $tmpTxtFile -ErrorAction SilentlyContinue
    }
}
