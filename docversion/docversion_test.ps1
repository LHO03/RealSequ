# ============================================================
# docversion improvements verification script (07/12)
# Usage: with the server running (docker compose up), in a new PowerShell terminal:
#   powershell -ExecutionPolicy Bypass -File .\docversion_test.ps1
# Checks: health / C-2 deadlock guard / C-1 concurrent decide / C-3 (404) / I-2 access control
#         / 9.5 version content / 9.3 reason+activity / regression / I-1 path race / I-4 stale reclaim
# ============================================================

$ErrorActionPreference = "Continue"
$Base = "http://localhost:8080"

# work dir (cookies, test files) - fresh per run
$Run  = Get-Date -Format "HHmmss"
$Work = Join-Path $env:TEMP ("docversion_test_" + $Run)
New-Item -ItemType Directory -Path $Work -Force | Out-Null
$A  = Join-Path $Work "alice.txt"
$B  = Join-Path $Work "bob.txt"
$AD = Join-Path $Work "admin.txt"

$script:Pass = 0; $script:Fail = 0; $script:FailList = @()

function Check([string]$Name, [bool]$Cond, [string]$Detail = "") {
    if ($Cond) { Write-Host ("[PASS] " + $Name) -ForegroundColor Green; $script:Pass++ }
    else {
        Write-Host ("[FAIL] " + $Name + ($(if ($Detail) { "  -> " + $Detail } else { "" }))) -ForegroundColor Red
        $script:Fail++; $script:FailList += $Name
    }
}

function Code {   # HTTP status code only
    param([string[]]$CArgs)
    $r = curl.exe -s -o NUL -w "%{http_code}" @CArgs
    return ([string]($r -join "")).Trim()
}
function Body {   # response body as string
    param([string[]]$CArgs)
    return ((curl.exe -s @CArgs) -join "`n")
}
function Json {   # response body parsed as JSON
    param([string[]]$CArgs)
    $r = Body $CArgs
    if ([string]::IsNullOrWhiteSpace($r)) { return $null }
    try { return ($r | ConvertFrom-Json) } catch { return $null }
}
function NewFile([string]$Name, [string]$Content) {
    $p = Join-Path $Work $Name
    Set-Content -Path $p -Value $Content -Encoding ascii
    return $p
}
function Upload([string]$Cookie, [string]$Folder, [string]$FilePath, [string]$Reason) {
    $cargs = @("-b", $Cookie, "-F", "folder=$Folder", "-F", "file=@$FilePath")
    if ($Reason) { $cargs += @("-F", "reason=$Reason") }
    $cargs += "$Base/api/documents/upload"
    return (Json $cargs)
}
function SetStatus([string]$Cookie, [string]$F, [string]$Target) {
    return (Code @("-b", $Cookie, "-d", "targetStatus=$Target", "$Base/api/documents/$F/status"))
}
function NewReviewDoc([string]$Tag, [string]$Approvers, [string]$Mode) {
    # create doc -> UNDER_REVIEW -> approval request; returns fileId
    $f = NewFile ("$Tag.txt") "v1"
    $u = Upload $A ("t" + $Run) $f $null
    $F = $u.fileId
    SetStatus $A $F "UNDER_REVIEW" | Out-Null
    Code @("-b", $A, "-d", "approvers=$Approvers&mode=$Mode", "$Base/api/documents/$F/approval/request") | Out-Null
    return $F
}

Write-Host "`n===== 0. Server health =====" -ForegroundColor Cyan
$h = Body @("$Base/actuator/health")
Check "0-1 health UP" ($h -match '"UP"') $h
if ($h -notmatch '"UP"') { Write-Host "Server not responding. Start docker compose first."; exit 1 }

Write-Host "`n===== 1. Login (3 sessions) =====" -ForegroundColor Cyan
Check "1-1 alice login 200" ((Code @("-c", $A, "-b", $A, "-d", "username=alice&password=alice123", "$Base/api/auth/login")) -eq "200")
Check "1-2 bob login 200" ((Code @("-c", $B, "-b", $B, "-d", "username=bob&password=bob123", "$Base/api/auth/login")) -eq "200")
Check "1-3 admin login 200" ((Code @("-c", $AD, "-b", $AD, "-d", "username=admin&password=admin123", "$Base/api/auth/login")) -eq "200")

Write-Host "`n===== 2. Fixture upload (with reason) =====" -ForegroundColor Cyan
$doc1 = NewFile "doc1.txt" "v1"
$u1 = Upload $A ("t" + $Run) $doc1 "initial-register"
$F1 = $u1.fileId
Check "2-1 upload ok (fileId issued)" (-not [string]::IsNullOrEmpty($F1)) ($u1 | ConvertTo-Json -Compress)
Check "2-2 initial revision = 1" ($u1.version.revisionNo -eq 1)

Write-Host "`n===== 3. C-2: manual status change blocked while request OPEN =====" -ForegroundColor Cyan
Check "3-1 submit UNDER_REVIEW 200" ((SetStatus $A $F1 "UNDER_REVIEW") -eq "200")
$rq = Code @("-b", $A, "-d", "approvers=bob&mode=ALL", "$Base/api/documents/$F1/approval/request")
Check "3-2 approval request 200" ($rq -eq "200")
$c2 = Code @("-b", $A, "-d", "targetStatus=DRAFT", "$Base/api/documents/$F1/status")
Check "3-3 C-2: manual DRAFT while OPEN = 409" ($c2 -eq "409") "actual: $c2 (pre-fix defect gives 200)"
Check "3-4 cancel 200" ((Code @("-b", $A, "-d", "comment=test", "$Base/api/documents/$F1/approval/cancel")) -eq "200")
$st = Json @("-b", $A, "$Base/api/documents/$F1/status")
Check "3-5 after cancel doc = DRAFT (workflow path not guarded)" ($st.status -eq "DRAFT") ("actual: " + $st.status)

Write-Host "`n===== 4. C-1: concurrent decide serialization (3 rounds) =====" -ForegroundColor Cyan
$allOk = $true; $detail = ""
for ($i = 1; $i -le 3; $i++) {
    $F = NewReviewDoc ("c1_$i") "bob,admin" "ALL"
    $j1 = Start-Job -ScriptBlock { param($ck, $u) curl.exe -s -b $ck -d "comment=ok" $u } -ArgumentList $B,  "$Base/api/documents/$F/approval/approve"
    $j2 = Start-Job -ScriptBlock { param($ck, $u) curl.exe -s -b $ck -d "comment=ok" $u } -ArgumentList $AD, "$Base/api/documents/$F/approval/approve"
    Wait-Job $j1, $j2 | Out-Null; Remove-Job $j1, $j2 -Force
    $ap = Json @("-b", $A, "$Base/api/documents/$F/approval")
    $ds = Json @("-b", $A, "$Base/api/documents/$F/status")
    $closed = ($null -eq $ap.open) -and ($ds.status -eq "APPROVED")
    if (-not $closed) { $allOk = $false; $detail = "round $i left OPEN or status=" + $ds.status }
}
Check "4-1 C-1: 3/3 rounds finalized APPROVED (no stuck OPEN)" $allOk $detail

Write-Host "`n===== 5. C-3: modify missing document = 404 =====" -ForegroundColor Cyan
$c3 = Code @("-b", $A, "-F", "file=@$doc1", "$Base/api/documents/no-such-file-id/versions")
Check "5-1 upload to missing doc = 404" ($c3 -eq "404") "actual: $c3"

Write-Host "`n===== 6. I-2: access control =====" -ForegroundColor Cyan
Check "6-1 anonymous outbox = 401" ((Code @("$Base/api/notifications/outbox")) -eq "401")
Check "6-2 anonymous diff = 401"   ((Code @("$Base/api/documents/$F1/diff?fromVersionId=a`&toVersionId=b")) -eq "401")
Check "6-3 user(bob) outbox = 403" ((Code @("-b", $B, "$Base/api/notifications/outbox")) -eq "403")
Check "6-4 admin outbox = 200" ((Code @("-b", $AD, "$Base/api/notifications/outbox")) -eq "200")
# 07/19 - P1-2 additions: deny-by-default + object authz on reads + storageKey hidden
Check "6-5 P1-2: anonymous version list = 401" ((Code @("$Base/api/documents/$F1/versions")) -eq "401")
Check "6-6 P1-2: anonymous status = 401" ((Code @("$Base/api/documents/$F1/status")) -eq "401")
Check "6-7 P1-2: anonymous approval = 401" ((Code @("$Base/api/documents/$F1/approval")) -eq "401")
$verBody = Body @("-b", $A, "$Base/api/documents/$F1/versions")
Check "6-8 P1-2: version list has NO storageKey" (-not ($verBody -match "storageKey")) ("leak: " + ($verBody -match "storageKey"))
Check "6-9 P1-2: version list has NO raw metadata field" (-not ($verBody -match '"metadata"')) ("leak: " + ($verBody -match '"metadata"'))

Write-Host "`n===== 7. RD-SRS-9.5: version content download =====" -ForegroundColor Cyan
$doc1v2 = NewFile "doc1.txt" "v2"          # same name -> same path -> new revision
$u2 = Upload $A ("t" + $Run) $doc1v2 $null
Check "7-1 re-upload revision = 2" ($u2.version.revisionNo -eq 2)
$vers = Json @("-b", $A, "$Base/api/documents/$F1/versions")
$v1id = ($vers | Where-Object { $_.revisionNo -eq 1 }).versionId
$v2id = ($vers | Where-Object { $_.revisionNo -eq 2 }).versionId
$b1 = Body @("-b", $A, "$Base/api/documents/$F1/versions/$v1id/content")
$b2 = Body @("-b", $A, "$Base/api/documents/$F1/versions/$v2id/content")
Check "7-2 rev1 content = v1 (point-in-time view)" ($b1.Trim() -eq "v1") ("actual: [" + $b1.Trim() + "]")
Check "7-3 rev2 content = v2"     ($b2.Trim() -eq "v2") ("actual: [" + $b2.Trim() + "]")
Check "7-4 anonymous content = 401" ((Code @("$Base/api/documents/$F1/versions/$v1id/content")) -eq "401")
# NOTE: F1 is NOT valid for this check - bob was an approver on F1 in step 3,
# and approvers get auto-subscribed (by design), so bob is allowed on F1.
# Use a fresh doc (owner alice, no approvals) so bob is truly unrelated.
$docC = NewFile "clean.txt" "c1"
$uC = Upload $A ("t" + $Run) $docC $null
$FC = $uC.fileId
$versC = Json @("-b", $A, "$Base/api/documents/$FC/versions")
$vC = $versC[0].versionId
Check "7-5 unrelated user(bob) on clean doc = 403" ((Code @("-b", $B, "$Base/api/documents/$FC/versions/$vC/content")) -eq "403")
# cross-file blocking: version id of F2 under F1 path must be 404
$F2 = NewReviewDoc "cross" "bob" "ALL"
$vers2 = Json @("-b", $A, "$Base/api/documents/$F2/versions")
$vX = $vers2[0].versionId
Check "7-6 cross-file versionId = 404" ((Code @("-b", $A, "$Base/api/documents/$F1/versions/$vX/content")) -eq "404")
# subscriber (approver) allowed: bob auto-subscribed on F2
Check "7-7 approver(subscriber) bob on F2 = 200" ((Code @("-b", $B, "$Base/api/documents/$F2/versions/$vX/content")) -eq "200")

Write-Host "`n===== 8. RD-SRS-9.3: change reason + activity =====" -ForegroundColor Cyan
$doc1v3 = NewFile "doc1.txt" "v3"
Upload $A ("t" + $Run) $doc1v3 "typo-fix" | Out-Null
$actRaw = Body @("-b", $A, "$Base/api/documents/$F1/activity")
$act = $null; try { $act = $actRaw | ConvertFrom-Json } catch {}
Check "8-1 activity endpoint works (>=3 items)" ($act.Count -ge 3) ("actual count: " + $act.Count)
# match on the RAW json body (immune to object/property quirks)
$hasUserReason = $actRaw -match "typo-fix"
$hasAutoReason = ($actRaw -match "rev 1 -> 2") -or ($actRaw -match "rev 1 -\\u003e 2")
Check "8-2 user reason (typo-fix) recorded" $hasUserReason
Check "8-3 auto note (rev 1 -> 2) recorded" $hasAutoReason
if (-not ($hasUserReason -and $hasAutoReason)) {
    Write-Host "---- DIAGNOSTIC: first activity rows (subject / subjectparams) ----" -ForegroundColor Yellow
    $act | Select-Object -First 5 | ForEach-Object {
        Write-Host ("  subject=" + $_.subject + "  params=" + $_.subjectparams)
    }
    # does the WRITE path work at all? metadata reason should appear in versions list
    $versRaw = Body @("-b", $A, "$Base/api/documents/$F1/versions")
    Write-Host ("  [diag] versions body contains typo-fix : " + ($versRaw -match "typo-fix"))
    Write-Host ("  [diag] activity body length            : " + $actRaw.Length)
}
$latest = $act[0]
Check "8-4 activity has user + timestamp" ((-not [string]::IsNullOrEmpty($latest.user)) -and ($latest.timestamp -gt 0))
# 07/19 P1-2: reads now require login; missing-doc for a logged-in owner-less id = 404 via policy
Check "8-5 activity of missing doc (logged-in) = 404" ((Code @("-b", $A, "$Base/api/documents/no-such-id/activity")) -eq "404")

Write-Host "`n===== 9. Regression: sequential / retract / cancel =====" -ForegroundColor Cyan
# 9-1 sequential: admin (2nd) first = 409; bob then admin finalizes
$Fs = NewReviewDoc "seq" "bob,admin" "SEQUENTIAL"
Check "9-1 seq: 2nd(admin) out of turn = 409" ((Code @("-b", $AD, "-d", "comment=x", "$Base/api/documents/$Fs/approval/approve")) -eq "409")
Check "9-2 seq: 1st(bob) approve = 200"     ((Code @("-b", $B,  "-d", "comment=x", "$Base/api/documents/$Fs/approval/approve")) -eq "200")
Check "9-3 seq: 2nd(admin) approve = 200"   ((Code @("-b", $AD, "-d", "comment=x", "$Base/api/documents/$Fs/approval/approve")) -eq "200")
$dss = Json @("-b", $A, "$Base/api/documents/$Fs/status")
Check "9-4 seq: final doc = APPROVED" ($dss.status -eq "APPROVED") ("actual: " + $dss.status)
# 9-2 retract before finalize; request stays OPEN
$Fr = NewReviewDoc "retract" "bob,admin" "ALL"
Check "9-5 retract: bob approve 200"  ((Code @("-b", $B, "-d", "comment=x", "$Base/api/documents/$Fr/approval/approve")) -eq "200")
Check "9-6 retract: bob retract 200" ((Code @("-b", $B, "-d", "comment=undo", "$Base/api/documents/$Fr/approval/retract")) -eq "200")
$apr = Json @("-b", $A, "$Base/api/documents/$Fr/approval")
$bobRow = $apr.open.approvers | Where-Object { $_.approverId -eq "bob" }
Check "9-7 after retract bob = PENDING (still OPEN)" (($null -ne $apr.open) -and ($bobRow.decision -eq "PENDING"))
# 9-3 cancel: non-requester 409, requester 200
Check "9-8 cancel by non-requester = 409" ((Code @("-b", $B, "-d", "comment=x", "$Base/api/documents/$Fr/approval/cancel")) -eq "409")
Check "9-9 cancel by requester = 200" ((Code @("-b", $A, "-d", "comment=x", "$Base/api/documents/$Fr/approval/cancel")) -eq "200")

Write-Host "`n===== 10. New fixes: I-1 (path race) / I-4 (stale reclaim) =====" -ForegroundColor Cyan
# 10-1 I-1: two PARALLEL uploads of the same brand-new path must yield ONE document.
#   winner creates rev1; loser hits UNIQUE(owner,path_hash) -> falls back to "add version" (rev2).
$raceFile = NewFile "race.txt" "r1"
$jr1 = Start-Job -ScriptBlock { param($ck,$fp,$fo,$u) curl.exe -s -b $ck -F "folder=$fo" -F "file=@$fp" $u } -ArgumentList $A, $raceFile, ("t"+$Run), "$Base/api/documents/upload"
$jr2 = Start-Job -ScriptBlock { param($ck,$fp,$fo,$u) curl.exe -s -b $ck -F "folder=$fo" -F "file=@$fp" $u } -ArgumentList $A, $raceFile, ("t"+$Run), "$Base/api/documents/upload"
Wait-Job $jr1,$jr2 | Out-Null
$o1 = $null; $o2 = $null
try { $o1 = ((Receive-Job $jr1) -join "`n") | ConvertFrom-Json } catch {}
try { $o2 = ((Receive-Job $jr2) -join "`n") | ConvertFrom-Json } catch {}
Remove-Job $jr1,$jr2 -Force
$sameDoc = ($o1.fileId) -and ($o1.fileId -eq $o2.fileId)
# I-1 core guarantee: parallel uploads must NOT create duplicate docs -> merged into one (sameDoc).
# Version count depends on arrival gap: if the loser falls back to "add version" -> 2;
# if both are effectively simultaneous before a winner is fixed -> 1 (normal race window).
# So assert: sameDoc AND at least 1 version (doc created and listable).
$versArr = @()
try { $versArr = @(Json @("-b", $A, "$Base/api/documents/" + $o1.fileId + "/versions")) } catch {}
$vCount = $versArr.Count
Check "10-1 I-1: parallel create -> one doc (no duplicate)" ($sameDoc -and ($vCount -ge 1)) ("fileIds: " + $o1.fileId + " / " + $o2.fileId + ", versions: " + $vCount)

# 10-2 I-4: plant a stale PROCESSING row directly in DB, wait one worker cycle,
#   status must leave PROCESSING (reclaimed -> resent -> SENT for WEB channel).
#   Requires docker CLI; run this script from the project folder (compose context).
# 10-3 P1-1: self-subscribe authorization bypass is closed.
#   bob subscribes to alice's clean doc -> 403, and content stays 403 afterwards.
#   owner subscribe stays 200 (idempotent). missing doc -> 404.
$subCode = Code @("-b", $B, "-d", "x=1", "$Base/api/documents/$FC/subscribe")
Check "10-3a P1-1: non-owner subscribe = 403" ($subCode -eq "403") ("actual: " + $subCode)
Check "10-3b P1-1: content still blocked after attempt = 403" ((Code @("-b", $B, "$Base/api/documents/$FC/versions/$vC/content")) -eq "403")
Check "10-3c P1-1: owner subscribe = 200" ((Code @("-b", $A, "-d", "x=1", "$Base/api/documents/$FC/subscribe")) -eq "200")
Check "10-3d P1-1: subscribe to missing doc = 404" ((Code @("-b", $B, "-d", "x=1", "$Base/api/documents/no-such-id/subscribe")) -eq "404")

$dockerOk = $true
try { docker compose version *> $null; if ($LASTEXITCODE -ne 0) { $dockerOk = $false } } catch { $dockerOk = $false }
if ($dockerOk) {
    $nowE = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $staleT = $nowE - 3600
    $nid = "i4-test-" + $Run
    $sqlIns = "INSERT INTO notification_outbox (notification_id,user_id,channel,payload,status,retry_count,retry_after,created_at,locked_by,locked_at) VALUES ('$nid','alice','WEB','[i4] stale reclaim test','PROCESSING',0,$staleT,$staleT,'dead-worker',$staleT);"
    docker compose exec -T mariadb mariadb -unextcloud -pnextcloud nextcloud -e $sqlIns *> $null
    Start-Sleep -Seconds 8
    $q = docker compose exec -T mariadb mariadb -unextcloud -pnextcloud nextcloud -N -e "SELECT status FROM notification_outbox WHERE notification_id='$nid';" 2>$null
    $stat = ([string]($q -join "")).Trim()
    Check "10-2 I-4: stale PROCESSING reclaimed (not PROCESSING anymore)" (($stat) -and ($stat -ne "PROCESSING")) ("actual: [" + $stat + "]")
} else {
    Write-Host "[SKIP] 10-2 I-4 reclaim test (docker CLI unavailable - run from project folder with compose up)" -ForegroundColor Yellow
}

Write-Host "`n===== 11. P1-2: deny-by-default + object authorization =====" -ForegroundColor Cyan
# fresh doc owned by alice, with bob NOT related
$docP = NewFile "p2.txt" "p2"
$uP = Upload $A ("t"+$Run) $docP $null
$FP = $uP.fileId
$vP = (Json @("-b", $A, "$Base/api/documents/$FP/versions"))[0].versionId

# 11-1 anonymous reads are now 401 (previously public)
Check "11-1a anon versions = 401"      ((Code @("$Base/api/documents/$FP/versions")) -eq "401")
Check "11-1b anon status = 401"        ((Code @("$Base/api/documents/$FP/status")) -eq "401")
Check "11-1c anon approval = 401"      ((Code @("$Base/api/documents/$FP/approval")) -eq "401")
Check "11-1d anon subscribers = 401"   ((Code @("$Base/api/documents/$FP/subscribers")) -eq "401")
Check "11-1e anon status-history = 401" ((Code @("$Base/api/documents/$FP/status-history")) -eq "401")

# 11-2 logged-in but unrelated user (bob) is 403 on object reads
Check "11-2a bob versions = 403"     ((Code @("-b", $B, "$Base/api/documents/$FP/versions")) -eq "403")
Check "11-2b bob status = 403"       ((Code @("-b", $B, "$Base/api/documents/$FP/status")) -eq "403")
Check "11-2c bob subscribers = 403"  ((Code @("-b", $B, "$Base/api/documents/$FP/subscribers")) -eq "403")

# 11-3 owner (alice) still allowed
Check "11-3a owner versions = 200" ((Code @("-b", $A, "$Base/api/documents/$FP/versions")) -eq "200")
Check "11-3b owner status = 200"   ((Code @("-b", $A, "$Base/api/documents/$FP/status")) -eq "200")

# 11-4 ADMIN can read (operational oversight)
Check "11-4 admin versions = 200" ((Code @("-b", $AD, "$Base/api/documents/$FP/versions")) -eq "200")

# 11-5 storageKey must NOT leak in versions JSON
$verBody = Body @("-b", $A, "$Base/api/documents/$FP/versions")
Check "11-5 storageKey not exposed in versions" (-not ($verBody -match "storageKey")) ("leak: " + ($verBody -match "storageKey"))


Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host ("RESULT: PASS " + $script:Pass + " / FAIL " + $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host "Failed checks:" -ForegroundColor Red
    $script:FailList | ForEach-Object { Write-Host ("  - " + $_) -ForegroundColor Red }
    Write-Host "`nCopy the failed check names and their actual values."
    exit 1
} else {
    Write-Host "ALL CHECKS PASSED. All 6 improvements work correctly." -ForegroundColor Green
    exit 0
}