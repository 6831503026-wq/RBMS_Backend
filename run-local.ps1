$envFile = Join-Path $PSScriptRoot ".env"

$originalValues = @{}
$loadedNames = @()

try {
    if (Test-Path -LiteralPath $envFile -PathType Leaf) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith("#")) {
                continue
            }

            $separator = $line.IndexOf("=")
            if ($separator -le 0) {
                throw "Invalid environment entry in $envFile"
            }

            $name = $line.Substring(0, $separator).Trim()
            if ($name -notmatch "^[A-Za-z_][A-Za-z0-9_]*$") {
                throw "Invalid environment variable name in $envFile"
            }

            $value = $line.Substring($separator + 1).Trim()
            if ($value.Length -ge 2) {
                $first = $value.Substring(0, 1)
                $last = $value.Substring($value.Length - 1, 1)
                if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
            }

            if (-not $originalValues.ContainsKey($name)) {
                $originalValues[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
                $loadedNames += $name
            }
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }

    Push-Location $PSScriptRoot
    try {
        & .\mvnw.cmd spring-boot:run
        if ($LASTEXITCODE -ne 0) {
            throw "Spring Boot exited with code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $loadedNames) {
        [Environment]::SetEnvironmentVariable($name, $originalValues[$name], "Process")
    }
}
