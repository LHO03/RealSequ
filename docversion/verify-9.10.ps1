# ============================================================
# 9.10 보존 정책 복구 확인 스크립트
#
# 사용법: docker compose up 으로 서버가 뜬 상태에서, 새 PowerShell 창에서
#   powershell -ExecutionPolicy Bypass -File .\verify-9.10.ps1
#
# 무엇을 확인하나
#   1) 업로드 2회 -> 리비전 2개, 두 버전 모두 dlp_scans 행이 생긴다  <- 이게 전제
#   2) 보존 정책(FILE, maxVersions=1) 적용
#        수정 전: 500  (dlp_scans 외래키 RESTRICT -> 오류 1451 -> 롤백)
#        수정 후: 200  {"deleted":1}
#   3) 리비전 1과 그 검사 이력이 함께 사라지고, 리비전 2는 그대로 남는다
#
# 소요: 30초 이내. 비동기 워커를 기다리지 않는다
#       (검사 행은 업로드 커밋 직후 리스너가 즉시 적재하므로 PENDING 상태로도 외래키에 걸린다)
# ============================================================

$ErrorActionPreference = "Continue"
$Base = "http://localhost:8080"

$Run  = Get-Date -Format "HHmmss"
$Work = Join-Path $env:TEMP ("docversion_910_" + $Run)
New-Item -ItemType Directory -Path $Work -Force | Out-Null
$A  = Join-Path $Work "alice.txt"
$AD = Join-Path $Work "admin.txt"

$script:Pass = 0; $script:Fail = 0; $script:FailList = @()

function Check([string]$Name, [bool]$Cond, [string]$Detail = "") {
    if ($Cond) { Write-Host ("[PASS] " + $Name) -ForegroundColor Green; $script:Pass++ }
    else {
        Write-Host ("[FAIL] " + $Name + ($(if ($Detail) { "  -> " + $Detail } else { "" }))) -ForegroundColor Red
        $script:Fail++; $script:FailList += $Name
    }
}
function Code { param([string[]]$CArgs)
    $r = curl.exe -s -o NUL -w "%{http_code}" @CArgs
    return ([string]($r -join "")).Trim()
}
function Body { param([string[]]$CArgs) return ((curl.exe -s @CArgs) -join "`n") }
function Json { param([string[]]$CArgs)
    $r = Body $CArgs
    if ([string]::IsNullOrWhiteSpace($r)) { return $null }
    try { return ($r | ConvertFrom-Json) } catch { return $null }
}
function NewFile([string]$Name, [string]$Content) {
    $p = Join-Path $Work $Name
    Set-Content -Path $p -Value $Content -Encoding ascii
    return $p
}

Write-Host "`n===== 0. 서버 확인 =====" -ForegroundColor Cyan
$h = Body @("$Base/actuator/health")
Check "0-1 서버 기동 확인" ($h -match '"UP"') $h
if ($h -notmatch '"UP"') {
    Write-Host "서버가 응답하지 않습니다. 먼저 docker compose up --build 를 실행하세요." -ForegroundColor Yellow
    exit 1
}

Write-Host "`n===== 1. 로그인 =====" -ForegroundColor Cyan
Check "1-1 alice 로그인" ((Code @("-c",$A,"-b",$A,"-d","username=alice&password=alice123","$Base/api/auth/login")) -eq "200")
Check "1-2 admin 로그인" ((Code @("-c",$AD,"-b",$AD,"-d","username=admin&password=admin123","$Base/api/auth/login")) -eq "200")

Write-Host "`n===== 2. 문서 준비 (리비전 2개) =====" -ForegroundColor Cyan
$f1 = NewFile "r910_v1.txt" "first revision"
$u1 = Json @("-b",$A,"-F","folder=r910_$Run","-F","file=@$f1","$Base/api/documents/upload")
$F  = $u1.fileId
$V1 = $u1.version.versionId
Check "2-1 최초 업로드" (-not [string]::IsNullOrEmpty($F)) ($u1 | ConvertTo-Json -Compress)
Check "2-2 리비전 1" ($u1.version.revisionNo -eq 1)

$f2 = NewFile "r910_v2.txt" "second revision"
$u2 = Json @("-b",$A,"-F","file=@$f2","$Base/api/documents/$F/versions")
$V2 = $u2.versionId
Check "2-3 수정본 업로드 -> 리비전 2" ($u2.revisionNo -eq 2) ($u2 | ConvertTo-Json -Compress)

Write-Host "`n===== 3. 전제: 두 버전 모두 DLP 검사 행이 있어야 한다 =====" -ForegroundColor Cyan
Write-Host "  (검사 행이 없으면 수정 전 코드로도 성공해서 아무것도 확인되지 않습니다)" -ForegroundColor DarkGray
$s1 = Code @("-b",$A,"$Base/api/documents/$F/versions/$V1/dlp?scope=FULL")
$s2 = Code @("-b",$A,"$Base/api/documents/$F/versions/$V2/dlp?scope=FULL")
Check "3-1 리비전 1 검사 행 존재" ($s1 -eq "200") "actual: $s1"
Check "3-2 리비전 2 검사 행 존재" ($s2 -eq "200") "actual: $s2"
if ($s1 -ne "200") {
    Write-Host "  검사 행이 없습니다. dlp_scans 적재가 동작하지 않는 상태라 이 시험은 의미가 없습니다." -ForegroundColor Yellow
    exit 1
}

Write-Host "`n===== 4. 보존 정책 생성 (FILE, maxVersions=1) =====" -ForegroundColor Cyan
# autoCleanup=false: 자동 정리 워커가 60초마다 이 정책을 계속 적용하지 않도록 한다
$p = Json @("-b",$AD,"-d","scopeType=FILE&scopeId=$F&minDays=0&maxDays=0&maxVersions=1&autoCleanup=false","$Base/api/retention/policies")
$P = $p.id
Check "4-1 정책 생성" (-not [string]::IsNullOrEmpty($P)) ($p | ConvertTo-Json -Compress)

Write-Host "`n===== 5. 정책 적용 - 여기가 핵심입니다 =====" -ForegroundColor Cyan
$applyCode = Code @("-b",$AD,"-X","POST","$Base/api/retention/policies/$P/apply")
$applyBody = Body @("-b",$AD,"-X","POST","$Base/api/retention/policies/$P/apply")
if ($applyCode -eq "500") {
    Write-Host "  >>> 500입니다. 이것이 수정 전 증상입니다." -ForegroundColor Yellow
    Write-Host "  >>> dlp_scans 외래키(RESTRICT) 때문에 files_versions 삭제가 막혀 트랜잭션이 롤백됐습니다." -ForegroundColor Yellow
    Write-Host "  >>> RetentionPurgeService.purgeFile 수정이 적용되지 않은 상태입니다." -ForegroundColor Yellow
}
Check "5-1 정책 적용이 200 (500이 아님)" ($applyCode -eq "200") "actual: $applyCode"

# 첫 호출에서 이미 지워졌으면 두 번째 호출은 deleted=0 이므로, 첫 호출 결과로 판단한다
$firstApply = $applyBody
Check "5-2 삭제된 버전이 보고됨" ($firstApply -match '"deleted"') $firstApply

Write-Host "`n===== 6. 결과 확인 =====" -ForegroundColor Cyan
$vs = Json @("-b",$A,"$Base/api/documents/$F/versions")
$cnt = @($vs).Count
Check "6-1 버전이 1개만 남음" ($cnt -eq 1) "actual count: $cnt"
if ($cnt -ge 1) {
    Check "6-2 남은 것이 리비전 2 (현재 버전은 보호됨)" (@($vs)[0].revisionNo -eq 2) ("actual: " + @($vs)[0].revisionNo)
}

$a1 = Code @("-b",$A,"$Base/api/documents/$F/versions/$V1/dlp?scope=FULL")
$a2 = Code @("-b",$A,"$Base/api/documents/$F/versions/$V2/dlp?scope=FULL")
Check "6-3 지워진 버전의 검사 이력도 사라짐 (404)" ($a1 -eq "404") "actual: $a1"
Check "6-4 남은 버전의 검사 이력은 그대로 (200)" ($a2 -eq "200") "actual: $a2"

$act = Body @("-b",$A,"$Base/api/documents/$F/activity")
Check "6-5 감사 이력에 retention_deleted 기록" ($act -match "file_retention_deleted") "activity에 없음"
Check "6-6 삭제된 검사 건수가 기록됨 (dlpScansDeleted)" ($act -match "dlpScansDeleted") `
    "purgeFile의 감사 기록 변경이 적용되지 않았습니다 (동작 자체에는 영향 없음)"

Write-Host "`n===== 7. 정리 =====" -ForegroundColor Cyan
if ($P) {
    $d = Code @("-b",$AD,"-X","POST","$Base/api/retention/policies/$P/deactivate")
    Check "7-1 시험용 정책 비활성화" ($d -eq "200") "actual: $d"
}

Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host ("결과:  PASS " + $script:Pass + "  /  FAIL " + $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host "실패 항목:" -ForegroundColor Red
    $script:FailList | ForEach-Object { Write-Host ("  - " + $_) -ForegroundColor Red }
    Write-Host "`n5-1이 500이면 수정이 적용되지 않은 것입니다." -ForegroundColor Yellow
} else {
    Write-Host "9.10 보존 정책이 검사 이력이 있는 버전을 정상적으로 정리합니다." -ForegroundColor Green
}
Write-Host "작업 폴더: $Work" -ForegroundColor DarkGray
Write-Host "============================================`n" -ForegroundColor Cyan
