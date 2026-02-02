# 3-Pass Benchmark Runner for Multiple Models
# Runs the three_pass_with_context.py benchmark for each specified model

param(
    [string]$PdfPath = "C:\Users\Pratik\Downloads\HP First3Chapters.pdf",
    [string]$OutputDir = "C:\Users\Pratik\source\Storyteller\scripts\benchmarkResults",
    [int]$ContextSize = 8192,      # Reduced from 16384 to prevent KV cache exhaustion
    [int]$Port = 8080,
    [int]$ServerWaitSeconds = 15,
    [int]$NParallel = 1,           # Single slot for sequential requests
    [int]$BatchSize = 512          # Reasonable batch size for inference
)

$ScriptDir = "C:\Users\Pratik\source\Storyteller\scripts"
$LlamaServer = Join-Path $ScriptDir "llama-cpp\llama-server.exe"
$BenchmarkScript = Join-Path $ScriptDir "three_pass_with_context.py"
$ServerUrl = "http://localhost:$Port"

# Models to test - Gemma and Qwen only
$Models = @(
    @{ Name = "qwen3-1.7b-q4_k_m"; Path = "D:\Learning\Ai\Models\LLM\qwen3-1.7b-q4_k_m.gguf" },
    @{ Name = "qwen2.5-3b-instruct-q4_k_m"; Path = "D:\Learning\Ai\Models\LLM\Gwen 2.5B Q4 KM\qwen2.5-3b-instruct-q4_k_m.gguf" },
    @{ Name = "gemma-3-1b-it-q4_0"; Path = "D:\Learning\Ai\Models\LLM\gemma-3-1b-it-q4_0.gguf" },
    @{ Name = "gemma-3-4b-it-q4_0"; Path = "D:\Learning\Ai\Models\LLM\gemma-3-4b-it-q4_0.gguf" }
)

# Create output directory if needed
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

# Store results for summary
$AllResults = @()

Write-Host "=" * 70
Write-Host "3-PASS BENCHMARK - MULTI-MODEL COMPARISON"
Write-Host "=" * 70
Write-Host "PDF: $PdfPath"
Write-Host "Output Dir: $OutputDir"
Write-Host "Models to test: $($Models.Count)"
Write-Host ""

foreach ($model in $Models) {
    $modelName = $model.Name
    $modelPath = $model.Path

    Write-Host ""
    Write-Host "=" * 70
    Write-Host "TESTING MODEL: $modelName"
    Write-Host "=" * 70

    # Check if model file exists
    if (-not (Test-Path $modelPath)) {
        Write-Host "ERROR: Model not found at $modelPath"
        continue
    }

    # Kill any existing llama-server processes
    Write-Host "Stopping any existing llama-server..."
    Get-Process -Name "llama-server" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2

    # Start llama-server with this model
    # Using optimized settings: reduced context, single parallel slot, controlled batch size
    Write-Host "Starting llama-server with $modelName..."
    $serverArgs = "-m `"$modelPath`" -c $ContextSize --port $Port -np $NParallel -b $BatchSize --no-mmap"
    Write-Host "  Args: $serverArgs"
    $serverProcess = Start-Process -FilePath $LlamaServer -ArgumentList $serverArgs -PassThru -WindowStyle Minimized

    # Wait for server to be ready
    Write-Host "Waiting for server to start ($ServerWaitSeconds seconds)..."
    Start-Sleep -Seconds $ServerWaitSeconds

    # Check server health
    $serverReady = $false
    for ($i = 0; $i -lt 5; $i++) {
        try {
            $health = Invoke-RestMethod -Uri "$ServerUrl/health" -Method Get -TimeoutSec 5
            if ($health.status -eq "ok") {
                $serverReady = $true
                Write-Host "Server is ready!"
                break
            }
        } catch {
            Write-Host "Waiting for server... (attempt $($i + 1)/5)"
            Start-Sleep -Seconds 3
        }
    }

    if (-not $serverReady) {
        Write-Host "ERROR: Server failed to start for $modelName"
        if ($serverProcess) { Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue }
        continue
    }

    # Run the benchmark
    $timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $outputFile = Join-Path $OutputDir "HP_3pass_${modelName}_${timestamp}.txt"

    Write-Host "Running 3-pass benchmark..."
    Write-Host "Output: $outputFile"

    $startTime = Get-Date
    python $BenchmarkScript --pdf "$PdfPath" --output "$outputFile" --server "$ServerUrl" --model-name "$modelName"
    $endTime = Get-Date
    $duration = ($endTime - $startTime).TotalSeconds

    Write-Host "Benchmark completed in $([math]::Round($duration, 1)) seconds"

    # Store result info
    $AllResults += @{
        ModelName = $modelName
        OutputFile = $outputFile
        Duration = $duration
        Timestamp = $timestamp
    }

    # Stop the server
    Write-Host "Stopping llama-server..."
    if ($serverProcess) {
        Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Get-Process -Name "llama-server" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

Write-Host ""
Write-Host "=" * 70
Write-Host "ALL BENCHMARKS COMPLETE"
Write-Host "=" * 70
Write-Host ""
Write-Host "Results saved in: $OutputDir"
foreach ($r in $AllResults) {
    Write-Host "  - $($r.ModelName): $([math]::Round($r.Duration, 1))s"
}

# Return results for potential further processing
$AllResults
