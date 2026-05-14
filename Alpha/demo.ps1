# =============================================================================
# Alpha Demo Script - Scenario 1 (8 steps)
# English-only to avoid PowerShell encoding issues on Windows PowerShell 5.x
#
# Usage:
#   1. Spring Boot running on port 8080 (.\gradlew.bat bootRun)
#   2. PowerShell: .\demo.ps1
#   3. Press [Enter] at each step to proceed
#
# Options:
#   .\demo.ps1 -Auto       : auto-advance (1.5 sec between steps)
#   .\demo.ps1 -Reset      : truncate DB tables before starting
# =============================================================================

param(
    [switch]$Auto = $false,
    [switch]$Reset = $false
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$BaseUrl = "http://localhost:8080"
$FileId = "demo_file_001"
$Author = "alice"
$Approver = "bob"

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

function Write-Header($num, $title) {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host " [$num/8] $title" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
}

function Write-Note($text) {
    Write-Host "  $text" -ForegroundColor DarkGray
}

function Write-Request($method, $url) {
    Write-Host ""
    Write-Host "  >> $method $url" -ForegroundColor Yellow
}

function Write-Response($statusCode, $body) {
    if ($statusCode -ge 200 -and $statusCode -lt 300) {
        Write-Host "  << $statusCode OK" -ForegroundColor Green
    } else {
        Write-Host "  << $statusCode" -ForegroundColor Red
    }
    Write-Host ""
    if ($body) {
        $pretty = $body | ConvertFrom-Json | ConvertTo-Json -Depth 10
        Write-Host $pretty -ForegroundColor White
    }
}

function Invoke-Api($method, $path, $body = $null) {
    $url = "$BaseUrl$path"
    Write-Request $method $url

    try {
        if ($body) {
            $bodyJson = $body | ConvertTo-Json -Depth 10 -Compress
            Write-Host "  >> Body: $bodyJson" -ForegroundColor DarkYellow
            $response = Invoke-WebRequest -Uri $url -Method $method `
                -Body $bodyJson -ContentType "application/json; charset=utf-8" `
                -UseBasicParsing
        } else {
            $response = Invoke-WebRequest -Uri $url -Method $method -UseBasicParsing
        }
        Write-Response $response.StatusCode $response.Content
        return ($response.Content | ConvertFrom-Json)
    } catch {
        $err = $_.Exception.Response
        if ($err) {
            $reader = New-Object System.IO.StreamReader($err.GetResponseStream())
            $errBody = $reader.ReadToEnd()
            Write-Response ([int]$err.StatusCode) $errBody
        } else {
            Write-Host "  << ERROR: $($_.Exception.Message)" -ForegroundColor Red
        }
        return $null
    }
}

function Wait-Step($message = "Press [Enter] to continue") {
    if ($Auto) {
        Start-Sleep -Milliseconds 1500
    } else {
        Write-Host ""
        Write-Host "  -- $message --" -ForegroundColor Magenta
        $null = Read-Host
    }
}

function Reset-Database {
    Write-Host ""
    Write-Host "Resetting database..." -ForegroundColor Yellow
    Write-Host "  (TRUNCATE via docver-mariadb container)" -ForegroundColor DarkGray

    $sql = @"
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE notifications;
TRUNCATE approval_activity;
TRUNCATE approval_rule_approvers;
TRUNCATE approval_rule_requesters;
TRUNCATE approval_rules;
TRUNCATE systemtag_object_mapping;
TRUNCATE activity;
TRUNCATE files_versions;
SET FOREIGN_KEY_CHECKS = 1;
"@
    $sql | docker exec -i docver-mariadb mysql -udocver -pdocver_pw docver 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Done" -ForegroundColor Green
    } else {
        Write-Host "  FAILED - check 'docker ps' for docver-mariadb" -ForegroundColor Red
        Write-Host "  Alternative: run the SQL manually via Adminer (http://localhost:8081)" -ForegroundColor Red
    }
}

# -----------------------------------------------------------------------------
# Pre-check
# -----------------------------------------------------------------------------

Clear-Host
Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Alpha Demo - Scenario 1: Document Lifecycle Full Flow" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Author   : $Author" -ForegroundColor White
Write-Host "  Approver : $Approver" -ForegroundColor White
Write-Host "  FileId   : $FileId" -ForegroundColor White
Write-Host "  Server   : $BaseUrl" -ForegroundColor White
Write-Host ""

try {
    $null = Invoke-WebRequest -Uri "$BaseUrl/api/notifications?userId=__ping__" `
        -UseBasicParsing -TimeoutSec 3
    Write-Host "  [OK] Spring Boot server responds" -ForegroundColor Green
} catch {
    Write-Host "  [FAIL] Spring Boot server not responding" -ForegroundColor Red
    Write-Host "         First run: .\gradlew.bat bootRun (in another window)" -ForegroundColor Red
    exit 1
}

if ($Reset) {
    Reset-Database
}

Wait-Step "Press [Enter] to start"

# -----------------------------------------------------------------------------
# Step 1: Initial version
# -----------------------------------------------------------------------------
Write-Header 1 "Initial version (alice uploads file)"
Write-Note "Pseudocode mapping: createInitialVersion"
Write-Note "-> files_versions INSERT + 'version_created' notification"

$body = @{
    userId = $Author
    fileId = $FileId
    size = 1024
    mimeType = "text/plain"
}
$v1 = Invoke-Api "POST" "/api/versions/initial" $body
Wait-Step

# -----------------------------------------------------------------------------
# Step 2: Update document
# -----------------------------------------------------------------------------
Write-Header 2 "Update document (alice creates new version)"
Write-Note "Pseudocode mapping: onDocumentModified"
Write-Note "-> new versionId + 'version_updated' notification (review #1 fix)"

$body = @{
    userId = $Author
    fileId = $FileId
    size = 2048
    mimeType = "text/plain"
}
$v2 = Invoke-Api "POST" "/api/versions/update" $body
Wait-Step

# -----------------------------------------------------------------------------
# Step 3: alice notifications
# -----------------------------------------------------------------------------
Write-Header 3 "Check alice's notifications (auto-triggered)"
Write-Note "Pseudocode mapping: getUserNotifications"
Write-Note "-> expect 2 notifications: version_created + version_updated"

$alice_notifs = Invoke-Api "GET" "/api/notifications?userId=$Author"
if ($alice_notifs.Count -eq 2) {
    Write-Host ""
    Write-Host "  [OK] alice has $($alice_notifs.Count) notifications - as expected" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "  [WARN] alice has $($alice_notifs.Count) notifications - expected 2" -ForegroundColor Yellow
}
Wait-Step

# -----------------------------------------------------------------------------
# Step 4: Request approval
# -----------------------------------------------------------------------------
Write-Header 4 "Request approval (alice -> bob)"
Write-Note "Pseudocode mapping: processApprovalWorkflow REQUEST branch"
Write-Note "-> approval_rules INSERT + UNDER_REVIEW status + notify bob"

$body = @{
    userId = $Author
    fileId = $FileId
    comment = "Please review"
    approvers = @($Approver)
}
$rule = Invoke-Api "POST" "/api/approvals/request" $body
Wait-Step

# -----------------------------------------------------------------------------
# Step 5: bob notifications
# -----------------------------------------------------------------------------
Write-Header 5 "Check bob's notifications (approval requested)"
Write-Note "-> expect 1 unread notification: approval_requested"

$bob_notifs = Invoke-Api "GET" "/api/notifications?userId=$Approver&unreadOnly=true"
if ($bob_notifs.Count -eq 1 -and $bob_notifs[0].subject -eq "approval_requested") {
    Write-Host ""
    Write-Host "  [OK] bob received approval_requested" -ForegroundColor Green
}
Wait-Step

# -----------------------------------------------------------------------------
# Step 6: bob unread count
# -----------------------------------------------------------------------------
Write-Header 6 "bob unread count"
Write-Note "Pseudocode mapping: getUnreadCount (for UI badge)"

$count = Invoke-Api "GET" "/api/notifications/unread-count?userId=$Approver"
Write-Host ""
Write-Host "  Unread: $($count.unreadCount)" -ForegroundColor White
Wait-Step

# -----------------------------------------------------------------------------
# Step 7: bob decision
# -----------------------------------------------------------------------------
Write-Header 7 "bob approves"
Write-Note "Pseudocode mapping: processApprovalDecision (THRESHOLD+1)"
Write-Note "-> auth check -> activity log -> counter -> consensus -> APPROVED"
Write-Note "-> notify alice via notifyStakeholders (review #4 fix: Outbox unified)"

$body = @{
    userId = $Approver
    fileId = $FileId
    action = "APPROVE"
    comment = "Looks good, approved"
}
$decision = Invoke-Api "POST" "/api/approvals/decide" $body
Wait-Step

# -----------------------------------------------------------------------------
# Step 8: alice final notification
# -----------------------------------------------------------------------------
Write-Header 8 "Check alice's final notification"
Write-Note "-> expect approval_completed notification for alice"

$alice_final = Invoke-Api "GET" "/api/notifications?userId=$Author&unreadOnly=true"
$completed = $alice_final | Where-Object { $_.subject -eq "approval_completed" }
if ($completed) {
    Write-Host ""
    Write-Host "  [OK] approval_completed received - review #4 fix works" -ForegroundColor Green
    Write-Host "  Message: $($completed.message)" -ForegroundColor White
} else {
    Write-Host ""
    Write-Host "  [WARN] approval_completed not found" -ForegroundColor Red
}

# -----------------------------------------------------------------------------
# Done
# -----------------------------------------------------------------------------
Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Demo complete - Scenario 1 (8 steps)" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Final state:" -ForegroundColor White
Write-Host "    File   : $FileId" -ForegroundColor White
Write-Host "    Status : APPROVED (check via Adminer)" -ForegroundColor White
Write-Host "    Adminer: http://localhost:8081" -ForegroundColor DarkGray
Write-Host ""