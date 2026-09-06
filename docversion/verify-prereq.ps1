# ============================================================
# 다음주 측정 전제 3건 확인 스크립트
#
# 사용법: docker compose up 으로 서버가 뜬 상태에서, 새 PowerShell 창에서
#   powershell -ExecutionPolicy Bypass -File .\verify-prereq.ps1
#
# 확인 항목
#   F3  업로드 상한 - 5MB 문서가 올라가고, 상한 초과는 413으로 안내되는가
#   F13 추출 상태 고착 - 재검사 응답이 textReset 필드를 돌려주는가
#   (F4는 Error 주입이 필요해 JUnit 시험으로 확인한다: DlpScanResilienceTest)
#
# 소요: 1~2분. 30MB 파일 생성과 전송에 시간이 걸린다.
# ============================================================

$ErrorActionPreference = "Continue"
$Base = "http://localhost:8080"

$Run  = Get-Date -Format "HHmmss"
$Work = Join-Path $env:TEMP ("docversion_prereq_" + $Run)
New-Item -ItemType Directory -Path $Work -Force | Out-Null
$A = Join-Path $Work "alice.txt"

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

# 지정한 크기(MB)의 텍스트 파일을 만든다. 메모리에 통째로 올리지 않는다.
function NewSizedFile([string]$Name, [int]$Mb) {
    $p = Join-Path $Work $Name
    $line = ("A" * 1023)
    $sw = [System.IO.StreamWriter]::new($p, $false, [System.Text.Encoding]::ASCII)
    try { for ($i = 0; $i -lt ($Mb * 1024); $i++) { $sw.WriteLine($line) } }
    finally { $sw.Close() }
    return $p
}

Write-Host "`n===== 0. 서버 확인 =====" -ForegroundColor Cyan
$h = Body @("$Base/actuator/health")
Check "0-1 서버 기동" ($h -match '"UP"') $h
if ($h -notmatch '"UP"') {
    Write-Host "서버가 응답하지 않습니다. docker compose up --build 를 먼저 실행하세요." -ForegroundColor Yellow
    exit 1
}
Check "0-2 alice 로그인" ((Code @("-c",$A,"-b",$A,"-d","username=alice&password=alice123","$Base/api/auth/login")) -eq "200")

Write-Host "`n===== F3. 업로드 상한 =====" -ForegroundColor Cyan

Write-Host "  5MB 파일 생성 중..." -ForegroundColor DarkGray
$small = NewSizedFile "doc_5mb.txt" 5
$u = Json @("-b",$A,"-F","folder=pre_$Run","-F","file=@$small","$Base/api/documents/upload")
$F = $u.fileId
$V = $u.version.versionId
Check "F3-1 5MB 문서 업로드 성공" (-not [string]::IsNullOrEmpty($F)) `
    "수정 전에는 1MB 상한에 걸려 500이 났다. 응답: $($u | ConvertTo-Json -Compress)"

Write-Host "  30MB 파일 생성 중... (시간이 걸립니다)" -ForegroundColor DarkGray
$big = NewSizedFile "doc_30mb.txt" 30
$bigCode = Code @("-b",$A,"-F","folder=pre_$Run","-F","file=@$big","$Base/api/documents/upload")
Check "F3-2 상한 초과는 413" ($bigCode -eq "413") `
    "actual: $bigCode  (500이면 예외 매핑 누락, 000이면 연결 끊김 - max-swallow-size 확인)"

if ($bigCode -eq "413") {
    $bigBody = Body @("-b",$A,"-F","folder=pre_$Run","-F","file=@$big","$Base/api/documents/upload")
    Check "F3-3 응답에 상한값 안내 포함" ($bigBody -match "MB") "actual: $bigBody"
}

Write-Host "`n===== F13. 추출 상태 고착 해소 =====" -ForegroundColor Cyan
if ([string]::IsNullOrEmpty($V)) {
    Write-Host "  업로드가 실패해 이 절은 건너뜁니다." -ForegroundColor Yellow
} else {
    $re = Json @("-b",$A,"-X","POST","$Base/api/documents/$F/versions/$V/dlp/rescan?scope=FULL")
    Check "F13-1 재검사 요청 성공" ($re.ok -eq $true) ($re | ConvertTo-Json -Compress)
    Check "F13-2 응답에 textReset 필드 존재" ($re.PSObject.Properties.Name -contains "textReset") `
        "이 필드가 없으면 DlpController 수정이 반영되지 않은 것이다. 응답: $($re | ConvertTo-Json -Compress)"
    Write-Host "  (추출이 정상인 문서라 textReset=false가 정상입니다. 실제 되돌림은 JUnit에서 확인)" -ForegroundColor DarkGray
}

Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host ("결과:  PASS " + $script:Pass + "  /  FAIL " + $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host "실패 항목:" -ForegroundColor Red
    $script:FailList | ForEach-Object { Write-Host ("  - " + $_) -ForegroundColor Red }
} else {
    Write-Host "측정 전제가 갖춰졌습니다. 테스트 문서 제작으로 넘어갈 수 있습니다." -ForegroundColor Green
}
Write-Host "작업 폴더: $Work  (큰 파일이 남아 있으니 확인 후 지우세요)" -ForegroundColor DarkGray
Write-Host "============================================`n" -ForegroundColor Cyan
