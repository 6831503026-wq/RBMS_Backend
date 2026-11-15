$ErrorActionPreference = "Stop"
$baseUrl = "http://localhost:5001"

try {
    $credential = Get-Credential -Message "Enter your RBMS manager account"
    $loginBody = @{
        username = $credential.UserName
        password = [System.Net.NetworkCredential]::new("", $credential.Password).Password
    } | ConvertTo-Json

    $login = Invoke-RestMethod -Method Post `
        -Uri "$baseUrl/api/auth/login" `
        -ContentType "application/json" `
        -Body $loginBody

    if ($login.user.role -ne "manager") {
        throw "This account is not a manager account."
    }

    $result = Invoke-RestMethod -Method Post `
        -Uri "$baseUrl/api/notifications/test" `
        -Headers @{ Authorization = "Bearer $($login.token)" }

    if (-not $result.sent) {
        throw "Email test failed: $($result.error)"
    }

    Write-Host "Test email sent successfully." -ForegroundColor Green
}
catch {
    Write-Host "Email test failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
finally {
    $loginBody = $null
    $login = $null
    $credential = $null
}