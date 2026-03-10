# ---------------------------------------------------------------------------
# run.ps1 - Compile and run Java classes via Maven
#
# Usage:
#   .\run.ps1                          # sentiment test with built-in sample
#   .\run.ps1 ..\input\mydoc.txt       # sentiment test on plain-text file
#   .\run.ps1 ..\input\mydoc.docx      # sentiment test on Word document
#   .\run.ps1 -Parse ..\input\file.txt # speaker turn parsing only (no CoreNLP)
#   .\run.ps1 -Parse ..\input\file.docx
#   .\run.ps1 -Resolve ..\input\file.txt  # target resolution (who speaks to whom)
#   .\run.ps1 -Resolve ..\input\file.docx
#   .\run.ps1 -Score ..\input\file.txt    # full pipeline: parse + resolve + CoreNLP scoring
#   .\run.ps1 -Score ..\input\file.docx
#   .\run.ps1 -Score -v ..\input\file.docx # scoring with per-turn detail
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
$scriptDir  = $PSScriptRoot
$projectDir = (Resolve-Path (Join-Path $scriptDir "..")).Path
$mvnw       = Join-Path $projectDir "mvnw.cmd"

# -- Compile via Maven ------------------------------------------------------
Write-Host ""
Write-Host "[1/2] Compiling with Maven..."
Push-Location $projectDir
try {
    & $mvnw compile -q
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Maven compilation failed."
        exit 1
    }
} finally {
    Pop-Location
}
Write-Host "    Compilation succeeded."
Write-Host ""

# -- Build classpath from Maven dependencies --------------------------------
# Maven stores compiled classes in target/classes and dependencies in ~/.m2/
Push-Location $projectDir
$cpOutput = & $mvnw -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt" 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to resolve Maven classpath."
    Pop-Location
    exit 1
}
$depCp = Get-Content (Join-Path $projectDir "target\cp.txt") -Raw
$cp = (Join-Path $projectDir "target\classes") + ";" + $depCp.Trim()
Pop-Location

# -- Resolve input file (handle .docx) ------------------------------------
$inputArg   = $null
$tmpTxtFile = $null
$parseMode  = $false

# Check for mode flags
$fileArgs = @()
$resolveMode = $false
$scoreMode   = $false
$verboseMode = $false
foreach ($a in $args) {
    if ($a -eq "-Parse")       { $parseMode = $true }
    elseif ($a -eq "-Resolve") { $resolveMode = $true }
    elseif ($a -eq "-Score")   { $scoreMode = $true }
    elseif ($a -eq "-v")       { $verboseMode = $true }
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
$mainClass = if ($scoreMode) { "TurnScorer" } elseif ($resolveMode) { "TargetResolver" } elseif ($parseMode) { "SpeakerTurnParser" } else { "SentimentTest" }
Write-Host "[2/2] Running $mainClass..."
Write-Host ""

$javaArgs = @(
    "-Xmx4g",    # CoreNLP models need ~2-3 GB; 4 GB is safe
    "-cp", $cp,
    $mainClass
)

if ($verboseMode -and $scoreMode) { $javaArgs += "-v" }

# For Score mode, always write structured output to output/
if ($scoreMode) {
    $outputPath = Join-Path $projectDir "output"
    if (-not (Test-Path $outputPath)) { New-Item -ItemType Directory -Path $outputPath | Out-Null }
    $javaArgs += @("-o", (Resolve-Path $outputPath).Path)
}

if ($inputArg) { $javaArgs += $inputArg }

try {
    & java @javaArgs
} finally {
    if ($tmpTxtFile -and (Test-Path $tmpTxtFile)) {
        Remove-Item $tmpTxtFile -ErrorAction SilentlyContinue
    }
}
