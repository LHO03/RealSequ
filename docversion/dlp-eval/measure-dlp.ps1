# ============================================================
# DLP 탐지율 측정 스크립트 (RD-SRS-5.1 / 5.4)
#
# 사용법
#   1) docker compose up --build 로 서버를 띄운다
#   2) 새 PowerShell 창에서
#        cd <프로젝트>\dlp-eval
#        powershell -ExecutionPolicy Bypass -File .\measure-dlp.ps1
#
# 하는 일
#   documents\ 의 시험 문서를 모두 업로드하고, 배경 검사가 끝날 때까지 기다린 뒤,
#   answer-key.csv 의 정답과 대조해 혼동행렬과 지표를 낸다.
#
# 읽는 법 — 세 가지를 따로 본다
#   * 전체 지표      : 탐지율(재현율) / 정밀도 / 오탐율
#   * 그룹별 결과    : 함정 문서가 의도대로 걸렸는지 (알려진 결함의 실측 확인)
#   * 형식별 결과    : txt와 docx/pdf의 차이 = 텍스트 추출 경로의 손실분
#
# 판정 불가(UNDETERMINED)는 혼동행렬에서 제외하고 따로 센다.
# "검사하지 못함"을 "민감하지 않음"으로 세면 유출 차단이 무력화된 것을 못 본다.
# ============================================================

$ErrorActionPreference = "Continue"

$Base       = "http://localhost:8080"
$User       = "alice"
$Pass       = "alice123"
$ScanTimeout = 300        # 검사 완료를 기다리는 최대 초 (문서 51건 기준 넉넉히)
$PollEvery   = 3

$Here    = Split-Path -Parent $MyInvocation.MyCommand.Path
$DocsDir = Join-Path $Here "documents"
$KeyPath = Join-Path $Here "answer-key.csv"
$Stamp   = Get-Date -Format "yyyyMMdd_HHmmss"
$OutCsv  = Join-Path $Here ("results_" + $Stamp + ".csv")

$Jar = Join-Path $env:TEMP ("dlpmeasure_" + $Stamp + ".txt")

$RespFile = Join-Path $env:TEMP ("dlpresp_" + $Stamp + ".txt")

# 응답을 콘솔이 아니라 파일로 받아 UTF-8로 직접 읽는다.
#
# PowerShell은 네이티브 명령(curl.exe)의 표준출력을 '콘솔 코드페이지'로 해석한다.
# 한국어 Windows는 CP949이므로 서버가 보낸 UTF-8 한글이 깨지는데, 그 과정에서
# 따옴표까지 잃어버려 JSON 구조 자체가 망가진다. 실제로 이렇게 나왔다.
#
#     "displayName":"?대찓?쇱＜??,"score":10      <- 닫는 따옴표가 사라졌다
#
# [Console]::OutputEncoding을 UTF-8로 바꾸면 해결되지만, 콘솔 코드페이지와
# 어긋나면서 출력이 겹쳐 찍히는 부작용이 있었다. 파일로 받으면 콘솔 해석 경로를
# 통째로 건너뛰므로 양쪽 문제가 함께 사라진다.
function Fetch { param([string[]]$CArgs)
    $code = ([string]((curl.exe -s -o $RespFile -w "%{http_code}" @CArgs) -join "")).Trim()
    $body = ""
    if (Test-Path $RespFile) {
        try { $body = [IO.File]::ReadAllText($RespFile, [Text.Encoding]::UTF8) } catch { }
    }
    return [pscustomobject]@{ Code = $code; Body = $body }
}
function Body { param([string[]]$CArgs) return (Fetch $CArgs).Body }
function Code { param([string[]]$CArgs) return (Fetch $CArgs).Code }
function Json { param([string[]]$CArgs)
    $b = (Fetch $CArgs).Body
    if ([string]::IsNullOrWhiteSpace($b)) { return $null }
    try { return ($b | ConvertFrom-Json) } catch { return $null }
}

# ------------------------------------------------------------
# 0. 준비 확인
# ------------------------------------------------------------
Write-Host "`n===== 0. 준비 확인 =====" -ForegroundColor Cyan

if (-not (Test-Path $KeyPath)) {
    Write-Host "정답표를 찾을 수 없습니다: $KeyPath" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $DocsDir)) {
    Write-Host "문서 폴더를 찾을 수 없습니다: $DocsDir" -ForegroundColor Red
    exit 1
}

$health = Body @("$Base/actuator/health")
if ($health -notmatch '"UP"') {
    Write-Host "서버가 응답하지 않습니다. docker compose up --build 를 먼저 실행하세요." -ForegroundColor Red
    exit 1
}
Write-Host "  서버 기동 확인" -ForegroundColor DarkGray

# 규칙이 적재되어 있는지 먼저 본다. 0건이면 모든 문서가 '민감하지 않음'으로
# 판정되어 측정 결과가 통째로 무의미해진다. 여기서 걸러내는 편이 낫다.
# "규칙이 0건이다"와 "규칙을 물어보지 못했다"는 원인이 전혀 다르므로 구분해서 보고한다.
# 한 메시지로 뭉뚱그리면 관리자 로그인 실패나 인가 오류를 규칙 문제로 오인하게 된다.
$adminJar = Join-Path $env:TEMP ("dlpadmin_" + $Stamp + ".txt")
$adminLogin = Code @("-c",$adminJar,"-b",$adminJar,"-d","username=admin&password=admin123","$Base/api/auth/login")
if ($adminLogin -ne "200") {
    Write-Host "  admin 로그인 실패 (HTTP $adminLogin) — 규칙 적재 여부를 확인할 수 없습니다." -ForegroundColor Red
    Write-Host "  demo 프로필로 기동했는지 확인하세요(SPRING_PROFILES_ACTIVE=prod면 데모 계정이 없습니다)." -ForegroundColor Yellow
    exit 1
}

$rulesResp = Fetch @("-b",$adminJar,"$Base/api/dlp/rules")
$rulesCode = $rulesResp.Code
$rulesBody = $rulesResp.Body
if ($rulesCode -ne "200") {
    Write-Host "  규칙 조회 실패 (HTTP $rulesCode) — 규칙이 0건인지 아닌지 알 수 없습니다." -ForegroundColor Red
    Write-Host ("  응답: " + $rulesBody) -ForegroundColor DarkGray
    exit 1
}

$rules = $null
try { $rules = $rulesBody | ConvertFrom-Json } catch { }
if ($null -eq $rules) {
    Write-Host "  규칙 응답을 해석할 수 없습니다." -ForegroundColor Red
    Write-Host ("  응답: " + $rulesBody) -ForegroundColor DarkGray
    exit 1
}

# count는 PowerShell이 객체에 자동으로 붙이는 이름과 겹친다. 응답의 값을 명시적으로 집는다.
$ruleCount = 0
if ($null -ne $rules.PSObject.Properties["count"]) {
    $ruleCount = [int]$rules.PSObject.Properties["count"].Value
}
if ($ruleCount -eq 0) {
    Write-Host "  DLP 활성 규칙이 0건입니다. 이 상태로는 모든 문서가 '민감하지 않음'으로 판정됩니다." -ForegroundColor Red
    Write-Host ("  응답: " + $rulesBody) -ForegroundColor DarkGray
    Write-Host "  앱 기동 로그에서 DbRuleProvider 메시지를 확인하세요:" -ForegroundColor Yellow
    Write-Host "    docker compose logs app | Select-String DbRuleProvider" -ForegroundColor Yellow
    exit 1
}
Write-Host ("  규칙 " + $ruleCount + "건 적재, 임계값 " + $rules.threshold) -ForegroundColor DarkGray

$login = Code @("-c",$Jar,"-b",$Jar,"-d","username=$User&password=$Pass","$Base/api/auth/login")
if ($login -ne "200") {
    Write-Host "  로그인 실패 (HTTP $login)" -ForegroundColor Red
    exit 1
}
Write-Host "  $User 로그인" -ForegroundColor DarkGray

$key = Import-Csv -Path $KeyPath
Write-Host ("  정답표 " + $key.Count + "행") -ForegroundColor DarkGray

# ------------------------------------------------------------
# 1. 업로드
# ------------------------------------------------------------
Write-Host "`n===== 1. 업로드 =====" -ForegroundColor Cyan

$folder = "dlpeval_$Stamp"
$items = @()
$n = 0

foreach ($row in $key) {
    $n++
    $path = Join-Path $DocsDir $row.file
    if (-not (Test-Path $path)) {
        Write-Host ("  [건너뜀] 파일 없음: " + $row.file) -ForegroundColor Yellow
        continue
    }

    $up = Json @("-b",$Jar,"-F","folder=$folder","-F","file=@$path","$Base/api/documents/upload")
    if ($null -eq $up -or [string]::IsNullOrEmpty($up.fileId)) {
        Write-Host ("  [실패] " + $row.file) -ForegroundColor Red
        $items += [pscustomobject]@{
            Row = $row; FileId = ""; VersionId = ""
            Status = "UPLOAD_FAILED"; Verdict = ""; Score = ""; Findings = ""; Note = "업로드 실패"
        }
        continue
    }

    $items += [pscustomobject]@{
        Row = $row
        FileId = $up.fileId
        VersionId = $up.version.versionId
        Status = "PENDING"; Verdict = ""; Score = ""; Findings = ""; Note = ""
    }
    Write-Progress -Activity "업로드" -Status $row.file -PercentComplete (100 * $n / $key.Count)
}
Write-Progress -Activity "업로드" -Completed
Write-Host ("  " + @($items | Where-Object { $_.FileId -ne "" }).Count + "건 업로드 완료") -ForegroundColor DarkGray

# ------------------------------------------------------------
# 2. 검사 완료 대기
#
# 검사는 업로드 응답 경로가 아니라 배경 작업자가 수행한다(기본 15초 주기).
# 따라서 업로드 직후에는 아직 PENDING이다. 여기서 기다린다.
# ------------------------------------------------------------
Write-Host "`n===== 2. 검사 완료 대기 (최대 $ScanTimeout 초) =====" -ForegroundColor Cyan

$deadline = (Get-Date).AddSeconds($ScanTimeout)
$pending = @($items | Where-Object { $_.FileId -ne "" })

while ((Get-Date) -lt $deadline) {
    $left = @()
    foreach ($it in $pending) {
        $scan = Json @("-b",$Jar,"$Base/api/documents/$($it.FileId)/versions/$($it.VersionId)/dlp?scope=FULL")
        if ($null -eq $scan) { $left += $it; continue }

        $it.Status = [string]$scan.status
        if ($it.Status -eq "COMPLETED" -or $it.Status -eq "FAILED") {
            $it.Verdict = [string]$scan.verdict
            $it.Score   = [string]$scan.totalScore
            $it.Note    = [string]$scan.note
            if ($null -ne $scan.findings) {
                $g = $scan.findings | Group-Object patternName | Sort-Object Name
                $it.Findings = (($g | ForEach-Object { $_.Name + "x" + $_.Count }) -join " ")
            }
        } else {
            $left += $it
        }
    }
    $pending = @($left)
    if ($pending.Count -eq 0) { break }

    $done = $items.Count - $pending.Count
    Write-Progress -Activity "검사 대기" -Status "$done / $($items.Count) 완료" `
        -PercentComplete (100 * $done / $items.Count)
    Start-Sleep -Seconds $PollEvery
}
Write-Progress -Activity "검사 대기" -Completed

if ($pending.Count -gt 0) {
    Write-Host ("  경고: " + $pending.Count + "건이 시간 안에 끝나지 않았습니다.") -ForegroundColor Yellow
    Write-Host "  워커 주기(기본 15초)와 문서 크기를 고려해 ScanTimeout을 늘려 보세요." -ForegroundColor Yellow
    foreach ($p in $pending) {
        $p.Verdict = "TIMEOUT"
        Write-Host ("    미완료: " + $p.Row.file + " (마지막 상태 " + $p.Status + ")") -ForegroundColor DarkGray
    }
} else {
    Write-Host "  전건 검사 완료" -ForegroundColor DarkGray
}

# ------------------------------------------------------------
# 3. 집계
# ------------------------------------------------------------
Write-Host "`n===== 3. 결과 =====" -ForegroundColor Cyan

$results = foreach ($it in $items) {
    $r = $it.Row
    $verdict = $it.Verdict
    # switch가 아니라 if/elseif를 쓴다. switch의 괄호 패턴은 평가 규칙이 미묘해서
    # 집계 전체가 조용히 틀어질 수 있고, 이 결과가 곧 보고용 지표이기 때문이다.
    #
    # 판정 불가(UNDETERMINED)는 TP/FP/TN/FN 어디에도 넣지 않는다. 검사하지 못한 문서를
    # "민감하지 않음"으로 세면 유출 차단이 무력화된 상태가 좋은 점수로 보고된다.
    $outcome = "ERROR"
    if ($verdict -eq "UNDETERMINED") {
        $outcome = "UNDETERMINED"
    } elseif ($verdict -eq "SENSITIVE" -and $r.truth -eq "SENSITIVE") {
        $outcome = "TP"
    } elseif ($verdict -eq "NOT_SENSITIVE" -and $r.truth -eq "SENSITIVE") {
        $outcome = "FN"
    } elseif ($verdict -eq "SENSITIVE" -and $r.truth -eq "NOT_SENSITIVE") {
        $outcome = "FP"
    } elseif ($verdict -eq "NOT_SENSITIVE" -and $r.truth -eq "NOT_SENSITIVE") {
        $outcome = "TN"
    }
    [pscustomobject]@{
        file = $r.file; doc_id = $r.doc_id; format = $r.format; group = $r.group
        truth = $r.truth; verdict = $verdict; outcome = $outcome
        score = $it.Score; findings = $it.Findings
        expected_verdict = $r.engine_predicts; expected_score = $r.engine_score
        matches_expectation = $(if ($verdict -eq $r.engine_predicts) { "Y" } else { "N" })
        status = $it.Status; note = $it.Note; measure_note = $r.note
    }
}

$results | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8
Write-Host "  상세 결과: $OutCsv" -ForegroundColor DarkGray

function Metrics($set, $label) {
    $TP = @($set | Where-Object { $_.outcome -eq "TP" }).Count
    $FP = @($set | Where-Object { $_.outcome -eq "FP" }).Count
    $TN = @($set | Where-Object { $_.outcome -eq "TN" }).Count
    $FN = @($set | Where-Object { $_.outcome -eq "FN" }).Count
    $UN = @($set | Where-Object { $_.outcome -eq "UNDETERMINED" }).Count
    $ER = @($set | Where-Object { $_.outcome -eq "ERROR" }).Count

    $recall    = if (($TP + $FN) -gt 0) { [math]::Round(100.0 * $TP / ($TP + $FN), 1) } else { "-" }
    $precision = if (($TP + $FP) -gt 0) { [math]::Round(100.0 * $TP / ($TP + $FP), 1) } else { "-" }
    $fpr       = if (($FP + $TN) -gt 0) { [math]::Round(100.0 * $FP / ($FP + $TN), 1) } else { "-" }

    Write-Host ("  {0,-12} TP {1,3}  FP {2,3}  TN {3,3}  FN {4,3}  판정불가 {5,3}  오류 {6,3}   탐지율 {7,5}%  정밀도 {8,5}%  오탐율 {9,5}%" `
        -f $label, $TP, $FP, $TN, $FN, $UN, $ER, $recall, $precision, $fpr)
}

Write-Host "`n-- 전체 --" -ForegroundColor White
Metrics $results "전체"

Write-Host "`n-- 형식별 (txt와의 차이가 텍스트 추출 경로의 손실분) --" -ForegroundColor White
foreach ($f in @("txt","docx","pdf")) {
    $set = @($results | Where-Object { $_.format -eq $f })
    if ($set.Count -gt 0) { Metrics $set $f }
}

Write-Host "`n-- 그룹별 --" -ForegroundColor White
foreach ($g in ($results | Select-Object -ExpandProperty group -Unique | Sort-Object)) {
    Metrics ($results | Where-Object { $_.group -eq $g }) $g
}

Write-Host "`n-- 문서별 (txt 기준) --" -ForegroundColor White
Write-Host ("  {0,-5} {1,-11} {2,-14} {3,-14} {4,5} {5,-5} {6}" `
    -f "ID","그룹","정답","판정","점수","결과","탐지")
Write-Host ("  " + ("-" * 96)) -ForegroundColor DarkGray
foreach ($r in ($results | Where-Object { $_.format -eq "txt" } | Sort-Object doc_id)) {
    $color = switch ($r.outcome) {
        "TP" { "Green" } "TN" { "Green" }
        "FP" { "Yellow" } "FN" { "Red" }
        default { "Magenta" }
    }
    Write-Host ("  {0,-5} {1,-11} {2,-14} {3,-14} {4,5} {5,-5} {6}" `
        -f $r.doc_id, $r.group, $r.truth, $r.verdict, $r.score, $r.outcome, $r.findings) `
        -ForegroundColor $color
}

# 모사와 실측이 어긋난 건 — 정답표를 만든 계산이 서버 동작과 다르다는 뜻이므로
# 지표보다 먼저 확인해야 한다.
$mismatch = @($results | Where-Object { $_.matches_expectation -eq "N" -and $_.verdict -ne "TIMEOUT" })
if ($mismatch.Count -gt 0) {
    Write-Host "`n-- 정답표 예상과 실측이 다른 건 --" -ForegroundColor Yellow
    Write-Host "  (정답표는 서버 엔진을 모사해 계산한 값입니다. 어긋난다면 모사가 틀렸거나" -ForegroundColor DarkGray
    Write-Host "   서버 규칙이 정답표 작성 시점과 달라진 것이므로 지표보다 먼저 확인하세요.)" -ForegroundColor DarkGray
    foreach ($m in $mismatch) {
        Write-Host ("  {0,-28} 예상 {1,-14} 실측 {2,-14} 점수 {3}" `
            -f $m.file, $m.expected_verdict, $m.verdict, $m.score) -ForegroundColor Yellow
    }
}

Write-Host ""
