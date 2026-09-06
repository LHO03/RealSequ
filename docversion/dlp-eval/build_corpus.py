# -*- coding: utf-8 -*-
"""
DLP 탐지율 측정용 시험 문서 세트 생성기.

서버의 dlp-core 엔진(RuleBasedScanner + V14/V17 규칙)을 그대로 모사해
각 문서의 기대 판정을 계산하고, 문서 파일과 정답표를 함께 만든다.

모사 대상:
  - 정규식 5종 (V14 적재 + V17 계좌번호 수정)
  - 문맥 조건 (앞뒤 40자 안에 지정 어휘)
  - 검증기 SSN_CHECKSUM / LUHN (통과 시 score_verified 적용)
  - 겹침 해소 (RuleBasedScanner.resolveOverlaps)
  - 임계값 50, total >= threshold 이면 SENSITIVE

주의: 자바 정규식은 \\w \\b가 기본 ASCII다. 파이썬은 기본이 유니코드라
한글이 단어문자로 취급되어 경계 판정이 달라진다. re.ASCII로 맞춘다.
"""
import csv
import os
import re
import unicodedata

OUT = os.path.dirname(os.path.abspath(__file__))
DOCS = os.path.join(OUT, "documents")

THRESHOLD = 50

# ----------------------------------------------------------------------
# 규칙 (V14 적재값 + V17 계좌번호 수정 + V16 상한 해제)
# ----------------------------------------------------------------------
BANK_CTX = ("(국민|신한|우리|하나|농협|기업|씨티|SC제일|카카오뱅크|케이뱅크|토스뱅크|"
            "수협|새마을금고|신협|우체국|산업|대구|부산|경남|광주|전북|제주|"
            "계좌|예금주|입금|송금|이체|account)")

RULES = [
    dict(name="SSN", severity="HIGH", score=60, score_verified=100,
         validator="SSN_CHECKSUM", context=None, window=40,
         regex=r"\b(\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01]))[- ]?([1-8]\d{6})\b"),
    dict(name="CREDIT_CARD", severity="HIGH", score=60, score_verified=100,
         validator="LUHN", context=None, window=40,
         regex=(r"\b(?:3[47]\d{2}[- ]?\d{6}[- ]?\d{5}"
                r"|(?:4\d{3}|5[1-5]\d{2}|6011)[- ]?\d{4}[- ]?\d{4}[- ]?\d{4})\b")),
    dict(name="BANK_ACCOUNT", severity="MEDIUM", score=80, score_verified=80,
         validator=None, context=BANK_CTX, window=40,
         regex=(r"(?<!\+)\b(?!01[0-9][-.]?\d{3,4}[-.]?\d{4}\b)(?=[\d-]{13})"
                r"\d{2,6}-\d{2,6}-\d{2,6}(?:-\d{2,6})?\b(?!-\d)")),
    dict(name="PHONE", severity="LOW", score=20, score_verified=20,
         validator=None, context=None, window=40,
         regex=r"\b01[0-9][-. ]?\d{3,4}[-. ]?\d{4}\b"),
    dict(name="EMAIL", severity="LOW", score=10, score_verified=10,
         validator=None, context=None, window=40,
         regex=r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
]

SEV_ORDER = {"HIGH": 0, "MEDIUM": 1, "LOW": 2}


def ssn_checksum(matched):
    d = re.sub(r"\D", "", matched)
    if len(d) != 13:
        return False
    w = [2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5]
    s = sum(int(d[i]) * w[i] for i in range(12))
    return (11 - (s % 11)) % 10 == int(d[12])


def luhn(matched):
    d = re.sub(r"\D", "", matched)
    if not (12 <= len(d) <= 19):
        return False
    total, dbl = 0, False
    for ch in reversed(d):
        n = int(ch)
        if dbl:
            n *= 2
            if n > 9:
                n -= 9
        total += n
        dbl = not dbl
    return total % 10 == 0


VALIDATORS = {"SSN_CHECKSUM": ssn_checksum, "LUHN": luhn}


def scan(text):
    """RuleBasedScanner.doScan의 모사. (findings, total, verdict) 반환."""
    findings = []
    for rule in RULES:
        pat = re.compile(rule["regex"], re.ASCII)
        ctx = re.compile(rule["context"], re.ASCII) if rule["context"] else None
        for m in pat.finditer(text):
            start, end = m.start(), m.end()
            if end <= start:
                continue
            if ctx is not None:
                w = rule["window"]
                before = text[max(0, start - w):start]
                after = text[end:min(len(text), end + w)]
                if not (ctx.search(before) or ctx.search(after)):
                    continue
            v = VALIDATORS.get(rule["validator"]) if rule["validator"] else None
            verified = bool(v and v(m.group()))
            findings.append(dict(
                rule=rule["name"], severity=rule["severity"],
                score=rule["score_verified"] if verified else rule["score"],
                offset=start, length=end - start, verified=verified))

    # resolveOverlaps — 점수를 1차 키로 우선순위를 정하고, 채택된 구간과 겹치면 버린다.
    # (엔진의 F8 수정과 같은 기준. 종전에는 1차 키가 위치여서 먼저 나온 쪽이 이겼다.)
    findings.sort(key=lambda f: (-f["score"], -f["length"], f["offset"], f["rule"]))
    kept = []
    for c in findings:
        overlap = any(
            k["offset"] < c["offset"] + c["length"] and c["offset"] < k["offset"] + k["length"]
            for k in kept)
        if not overlap:
            kept.append(c)
    kept.sort(key=lambda f: f["offset"])   # 결과는 본문 순서로

    total = sum(f["score"] for f in kept)
    return kept, total, ("SENSITIVE" if total >= THRESHOLD else "NOT_SENSITIVE")


# ----------------------------------------------------------------------
# 시험 문서
#
#   truth  — 사람이 보기에 민감한 문서인가 (측정의 정답)
#   group  — 진양성/진음성/함정
#   note   — 이 문서가 무엇을 재는지
# ----------------------------------------------------------------------
SLUGS = {
    "인사기록카드": "hr-record",          "채용지원서 사본": "job-application",
    "법인카드 사용내역": "corp-card",      "급여이체 요청서": "payroll-transfer",
    "비상연락망": "contact-list",          "뉴스레터 수신자 명단": "newsletter-list",
    "퇴직정산 통지서": "severance-notice",  "인사 정정 요청": "hr-correction",
    "주간 회의록": "weekly-minutes",       "서버 구성 메모": "server-notes",
    "당직 근무표": "duty-roster",          "행사 안내": "event-notice",
    "행사 일정표": "event-schedule",       "청구 안내문": "billing-notice",
    "사번 확인 요청": "empno-lookup",      "문의 접수 메모": "inquiry-memo",
    "고객센터 안내": "helpdesk-notice",     "세금계산서 요청": "tax-invoice",
    "정산 요청 메모": "settlement-memo",    "해외출장 정산": "travel-expense",
    "장문 보고서": "long-report",
    "농협 이체 요청": "nonghyup-transfer",   "발주 확인서": "purchase-order",
    "도서 구입 요청": "book-order",          "구계좌 안내문": "legacy-account",
}


def D(doc_id, title, truth, group, note, body, formats=("txt",)):
    return dict(id=doc_id, title=title, truth=truth, group=group,
                note=note, body=body, formats=formats)


ALL3 = ("txt", "docx", "pdf")

DOCUMENTS = [
    # ---------------- A. 진양성: 실제로 민감한 문서 ----------------
    D("A01", "인사기록카드", "SENSITIVE", "진양성",
      "주민등록번호 1건(체크섬 통과). 단일 항목만으로 임계값을 넘는 대표 사례",
      """인사기록카드

소속: 기술연구소 개발2팀
성명: 김도현
주민등록번호: 900101-1000006
입사일: 2019년 3월 4일
직급: 선임연구원

위 사항은 인사규정 제12조에 따라 관리된다.
""", ALL3),

    D("A02", "채용지원서 사본", "SENSITIVE", "진양성",
      "주민등록번호 형식만 맞고 체크섬 불통과 → 60점. 오타 섞인 실제 번호를 놓치지 않는지",
      """채용지원서 사본

지원분야: 백엔드 개발
성명: 이서준
주민등록번호: 880315-1234567
연락 가능 시간: 평일 오후

* 서류 반환은 제출일로부터 14일 이내 신청 가능합니다.
""", ("txt",)),

    D("A03", "법인카드 사용내역", "SENSITIVE", "진양성",
      "Visa 16자리, Luhn 통과 → 100점",
      """법인카드 사용내역 (2026년 7월)

카드번호: 4539-5787-6362-1486
사용자: 총무팀 박민지

07-03  사무용품      132,000원
07-11  출장 숙박     284,000원
07-25  회식비        410,000원

합계 826,000원. 영수증은 총무팀에 제출 완료.
""", ALL3),

    D("A04", "급여이체 요청서", "SENSITIVE", "진양성",
      "계좌번호 + 은행명 문맥 → 80점. 문맥 조건이 제대로 작동하는지",
      """급여이체 요청서

아래 계좌로 2026년 8월분 급여를 이체하여 주시기 바랍니다.

예금주: 정하늘
은행: 국민은행
계좌번호: 123-45-678901
금액: 3,820,000원

재무팀 확인 후 25일에 일괄 처리합니다.
""", ALL3),

    D("A05", "비상연락망", "SENSITIVE", "진양성",
      "휴대전화 3건 = 60점. 단독으로는 임계값 미달인 항목이 누적으로 넘는 사례",
      """개발2팀 비상연락망

김도현  010-2345-6789
이서준  011-345-6789
박민지  010.9876.5432

야간·휴일 장애 발생 시 위 순서대로 연락한다.
""", ALL3),

    D("A06", "뉴스레터 수신자 명단", "SENSITIVE", "진양성",
      "이메일 5건 = 정확히 50점, 임계값과 같다. 경계값 동작 확인 (F14)",
      """뉴스레터 수신자 명단 (일부)

dohyun.kim@realsecu.co.kr
seojun.lee@realsecu.co.kr
minji.park@pknu.ac.kr
haneul.jung@pknu.ac.kr
jiwoo.choi@example.com

발송 주기: 격주 화요일
""", ("txt",)),

    D("A07", "퇴직정산 통지서", "SENSITIVE", "진양성",
      "주민번호 + 계좌 + 전화 + 이메일 복합. 실무 문서에 가장 가까운 형태",
      """퇴직정산 통지서

성명: 최지우
주민등록번호: 850707-2000001
연락처: 010-1111-2222
이메일: jiwoo.choi@example.com

정산금 입금 계좌
  신한은행 110-234-567890
  금액 12,450,000원

지급 예정일: 2026년 9월 10일
""", ALL3),

    # ---------------- B. 진음성: 민감하지 않은 문서 ----------------
    D("B01", "주간 회의록", "NOT_SENSITIVE", "진음성",
      "숫자가 거의 없는 일반 업무 문서. 기본 오탐 없음 확인",
      """주간 회의록

일시: 2026년 8월 24일 월요일 오전 10시
장소: 3층 회의실

논의 사항
  1. 문서 버전관리 모듈 진행 상황 공유
  2. 다음 스프린트 범위 확정
  3. 시험 환경 구축 일정

결정 사항
  - 비교 기능 회귀 시험을 먼저 마친다
  - 탐지율 측정은 시험 문서 세트가 준비된 뒤 진행한다
""", ALL3),

    D("B02", "서버 구성 메모", "NOT_SENSITIVE", "진음성",
      "버전 번호·포트 번호·IP가 많은 기술 문서. 숫자 밀집 오탐 확인",
      """서버 구성 메모

MariaDB 10.11 LTS  포트 3306
Redis 7.2          포트 6379
애플리케이션        포트 8080
Adminer            포트 8081

JDK 21.0.12, Maven 3.9.16 기준으로 빌드한다.
힙은 -Xmx2g로 시작하고 부하 시험 결과를 보고 조정한다.
""", ALL3),

    D("B03", "당직 근무표", "NOT_SENSITIVE", "진음성",
      "휴대전화 2건 = 40점. 임계값 바로 아래인지 확인",
      """9월 당직 근무표

1주차  김도현  010-2345-6789
2주차  이서준  010-3456-7890

교대는 금요일 18시 기준이며, 변경은 팀장 승인이 필요하다.
""", ("txt",)),

    D("B04", "행사 안내", "NOT_SENSITIVE", "진음성",
      "이메일 4건 = 40점. 경계 바로 아래",
      """가을 워크숍 안내

문의처
  총무팀  admin@realsecu.co.kr
  기획팀  plan@realsecu.co.kr
  연구소  lab@pknu.ac.kr
  회계팀  fin@realsecu.co.kr

참가 신청은 8월 31일까지 받습니다.
""", ("txt",)),

    D("B05", "행사 일정표", "NOT_SENSITIVE", "진음성",
      "날짜만 있고 거래 어휘가 없다. 문맥 조건이 없을 때 계좌 규칙이 잠잠한지",
      """하반기 행사 일정표

회의일: 2024-01-15 에 진행합니다
발표회: 2024-03-22 오후 2시
정기점검: 2024-06-30 종일

장소는 추후 공지합니다.
""", ALL3),

    # ---------------- C. 함정: 알려진 오탐 (F5) ----------------
    # 정답은 '민감하지 않음'이지만 현재 규칙은 계좌번호로 잡는다.
    D("C01", "청구 안내문", "NOT_SENSITIVE", "함정-오탐",
      "F5: 날짜가 계좌번호로 잡힌다. '입금'이 문맥 조건을 통과시킨다",
      """청구 안내문

입금일: 2024-01-15 까지 처리 바랍니다.
청구 내역은 첨부 문서를 확인하여 주십시오.
문의는 회계팀으로 부탁드립니다.
""", ALL3),

    D("C02", "사번 확인 요청", "NOT_SENSITIVE", "함정-오탐",
      "F5: 사번이 계좌번호로 잡힌다. '예금주'가 문맥 조건을 통과시킨다",
      """사번 확인 요청

예금주 확인용 사번 12-3456-78 로 조회 부탁드립니다.
인사시스템 조회 권한은 팀장 승인 후 부여됩니다.
""", ("txt",)),

    D("C03", "문의 접수 메모", "NOT_SENSITIVE", "함정-오탐",
      "F5: 국가번호가 붙은 전화번호가 계좌번호로 잡힌다. V17 전방탐색을 우회한다",
      """문의 접수 메모

연락처 +82-10-1234-5678 (급여계좌 문의)
담당자 부재 시 대표번호로 안내할 것.
""", ("txt",)),

    D("C04", "고객센터 안내", "NOT_SENSITIVE", "함정-오탐",
      "F5: 유선 대표번호가 계좌번호로 잡힌다",
      """고객센터 안내

대표번호 02-1234-5678 로 문의 (계좌 안내)
상담 시간은 평일 09시부터 18시까지입니다.
""", ALL3),

    D("C05", "세금계산서 요청", "NOT_SENSITIVE", "함정-오탐",
      "F5: 사업자등록번호가 계좌번호로 잡힌다. 실무에서 가장 흔한 형태",
      """세금계산서 발행 요청

사업자등록번호 123-45-67890 (세금계산서 입금)
상호: 주식회사 리얼시큐
발행일: 매월 말일
""", ALL3),

    D("C06", "정산 요청 메모", "NOT_SENSITIVE", "함정-오탐",
      "F5 확장: 계좌번호 정규식의 구분자가 공백도 허용해, 무관한 두 숫자열을 "
      "공백 너머로 하나로 묶는다. 원래 기록된 오탐 5종에 없던 형태",
      """정산 요청 메모

입금 예정 금액은 1002-345 12-3456 두 건으로 나누어 처리합니다.
증빙은 회계팀에 제출하였습니다.
""", ALL3),

    # ---------------- D. 함정: 알려진 미탐 (F11) ----------------
    D("D01", "해외출장 정산", "SENSITIVE", "함정-미탐",
      "F11: Amex 15자리. 정규식이 4-4-4-4를 요구해 탐지되지 않는다",
      """해외출장 정산서

결제카드(Amex): 3782-822463-10005
사용자: 개발2팀 김도현
출장지: 도쿄
기간: 2026년 6월 3일 ~ 6월 7일

정산 서류는 귀국 후 5일 이내 제출.
""", ALL3),

    D("A08", "인사 정정 요청", "SENSITIVE", "진양성",
      "F8 겹침: 계좌번호 규칙이 앞 숫자와 주민번호 앞부분을 하나로 묶어 구간이 겹친다. "
      "탐지 목록에 SSN이 남아야 한다(BANK_ACCOUNT로 바뀌면 겹침 해소가 위치 기준으로 되돌아간 것)",
      """인사 정정 요청

계좌 이체 예정 1002-345 900101-1000006 확인 바랍니다.
정정 사유: 등록 정보 오기
""", ALL3),

    D("A09", "농협 이체 요청", "SENSITIVE", "진양성",
      "V18로 새로 잡히게 된 형태. 농협식 4묶음 계좌를 온전히 탐지하는지 "
      "(종전에는 앞 3묶음만 잘라서 보고했다)",
      """농협 이체 요청서

아래 계좌로 8월분 대금을 이체하여 주시기 바랍니다.

예금주: 김도현
농협 352-0123-4567-89
금액: 1,250,000원
""", ALL3),

    D("C07", "발주 확인서", "NOT_SENSITIVE", "함정-오탐",
      "V18 이후 남은 약점: 4묶음 숫자 식별자는 자릿수만으로 농협식 계좌와 구분되지 않는다. "
      "측정으로 확인된 오탐이 아니라 검토 중 떠올린 형태이므로, 규칙을 더 좁히지 않고 "
      "여기서 실제로 문제가 되는지 본다",
      """발주 확인서

입금 확인 후 발송합니다.
주문번호: 2024-1234-5678
발주일: 2026년 8월 20일
""", ALL3),

    D("C08", "도서 구입 요청", "NOT_SENSITIVE", "함정-오탐",
      "C07과 같은 갈래. ISBN이 4묶음 숫자라 계좌번호와 형태가 겹친다",
      """도서 구입 요청

입금 처리 후 아래 도서를 주문해 주십시오.
ISBN 978-89-1234-567
수량: 3부
""", ALL3),

    D("D02", "구계좌 안내문", "SENSITIVE", "함정-미탐",
      "V18에서 감수한 대가: 구분자에서 공백을 빼면서 공백으로 적은 계좌번호를 놓친다. "
      "공백 허용이 오탐의 주된 통로였으므로 맞바꾼 것이며, 실제로 문제가 되는지 여기서 잰다",
      """계좌 변경 안내

기존 국민은행 123 45 678901 계좌는 사용하지 않습니다.
신규 계좌로 입금하여 주시기 바랍니다.
""", ALL3),

    # ---------------- E. 추출 경계 ----------------
    D("E01", "장문 보고서", "SENSITIVE", "추출경계",
      "본문이 긴 문서 안에 주민번호 1건. 추출과 검사가 문서 끝까지 닿는지",
      "장문 보고서\n\n"
      + "\n".join(f"{i}. 본 절은 시험을 위한 채움 문단이다. 내용상 의미는 없으며 "
                  f"문서 길이를 확보하기 위한 것이다." for i in range(1, 121))
      + "\n\n[별첨] 담당자 주민등록번호: 900101-1000006\n", ALL3),
]


# ----------------------------------------------------------------------
# 파일 생성
# ----------------------------------------------------------------------
def write_txt(path, body):
    with open(path, "w", encoding="utf-8") as f:
        f.write(body)


def write_docx(path, title, body):
    from docx import Document
    from docx.shared import Pt
    d = Document()
    style = d.styles["Normal"]
    style.font.name = "맑은 고딕"
    style.font.size = Pt(10)
    for line in body.split("\n"):
        d.add_paragraph(line)
    d.save(path)


PDF_FONT_READY = False


def ensure_pdf_font():
    global PDF_FONT_READY
    if PDF_FONT_READY:
        return
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    # reportlab은 postscript 윤곽선(.ttc/CJK OTF)을 못 읽는다. 진짜 TrueType이 필요하다.
    for cand in ("/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
                 "/usr/share/fonts/truetype/nanum/NanumBarunGothic.ttf"):
        if os.path.exists(cand):
            pdfmetrics.registerFont(TTFont("KR", cand))
            PDF_FONT_READY = True
            return
    raise RuntimeError("한글 TrueType 글꼴을 찾지 못했습니다")


def write_pdf(path, title, body):
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas
    ensure_pdf_font()
    c = canvas.Canvas(path, pagesize=A4)
    w, h = A4
    y = h - 60
    c.setFont("KR", 10)
    for line in body.split("\n"):
        if y < 60:
            c.showPage()
            c.setFont("KR", 10)
            y = h - 60
        c.drawString(50, y, line)
        y -= 14
    c.save()


def main():
    os.makedirs(DOCS, exist_ok=True)
    rows = []

    for doc in DOCUMENTS:
        kept, total, predicted = scan(doc["body"])
        by_rule = {}
        for f in kept:
            by_rule[f["rule"]] = by_rule.get(f["rule"], 0) + 1
        hits = " ".join(f"{k}x{v}" for k, v in sorted(by_rule.items()))

        for fmt in doc["formats"]:
            # 파일명은 ASCII만 쓴다. PowerShell 5.1이 curl.exe에 인자를 넘길 때
            # 콘솔 코드페이지로 변환하므로, 한글 파일명은 로캘에 따라 열리지 않는다.
            # 한글 제목은 정답표의 title 열에 그대로 남는다.
            slug = SLUGS.get(doc["title"])
            if slug is None:
                raise KeyError(f"SLUGS에 '{doc['title']}' 항목을 추가하십시오")
            name = f"{doc['id']}_{slug}.{fmt}"
            path = os.path.join(DOCS, name)
            if fmt == "txt":
                write_txt(path, doc["body"])
            elif fmt == "docx":
                write_docx(path, doc["title"], doc["body"])
            elif fmt == "pdf":
                write_pdf(path, doc["title"], doc["body"])

            rows.append({
                "file": name,
                "doc_id": doc["id"],
                "format": fmt,
                "title": doc["title"],
                "group": doc["group"],
                "truth": doc["truth"],
                "engine_predicts": predicted,
                "engine_score": total,
                "engine_hits": hits,
                "agrees": "Y" if predicted == doc["truth"] else "N",
                "note": doc["note"],
            })

    key = os.path.join(OUT, "answer-key.csv")
    with open(key, "w", encoding="utf-8-sig", newline="") as f:
        wtr = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        wtr.writeheader()
        wtr.writerows(rows)

    # 요약 출력
    docs = {r["doc_id"]: r for r in rows}
    print(f"문서 {len(docs)}종, 파일 {len(rows)}개")
    print()
    print(f"{'ID':5} {'그룹':10} {'정답':14} {'엔진예상':14} {'점수':>5}  {'일치':4} 탐지")
    print("-" * 88)
    agree = 0
    for r in docs.values():
        if r["agrees"] == "Y":
            agree += 1
        g = r["group"] + " " * (10 - sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1
                                         for ch in r["group"]))
        print(f"{r['doc_id']:5} {g} {r['truth']:14} {r['engine_predicts']:14} "
              f"{r['engine_score']:>5}  {r['agrees']:4} {r['engine_hits']}")
    print("-" * 88)
    print(f"모사 기준 일치 {agree}/{len(docs)} "
          f"= {agree * 100.0 / len(docs):.1f}%  (실측이 이 값과 크게 다르면 모사가 틀린 것)")


if __name__ == "__main__":
    main()
