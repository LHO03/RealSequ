package com.docversion.dlp;

import com.docversion.dlp.validate.LuhnValidator;
import com.docversion.dlp.validate.SsnChecksumValidator;
import com.docversion.dlp.validate.Validators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 검증기 시험. (RD-SRS-5.4) */
class ValidatorTest {

    private final SsnChecksumValidator ssn = new SsnChecksumValidator();
    private final LuhnValidator luhn = new LuhnValidator();

    @Test
    @DisplayName("주민번호 검증번호가 맞으면 통과")
    void ssnValidChecksum() {
        assertTrue(ssn.isValid("010203-4567890"));
        assertTrue(ssn.isValid("0102034567890"), "구분자 없이도 동작해야 함");
    }

    @Test
    @DisplayName("주민번호 검증번호가 어긋나면 미통과")
    void ssnInvalidChecksum() {
        assertFalse(ssn.isValid("861203-1234567"));
        assertFalse(ssn.isValid("999999-9999999"));
    }

    @Test
    @DisplayName("자릿수가 맞지 않으면 미통과")
    void ssnWrongLength() {
        assertFalse(ssn.isValid("010203-456789"));
        assertFalse(ssn.isValid(""));
        assertFalse(ssn.isValid(null));
    }

    @Test
    @DisplayName("Luhn 유효 카드번호는 통과")
    void luhnValid() {
        assertTrue(luhn.isValid("4539578763621486"));
        assertTrue(luhn.isValid("4539-5787-6362-1486"));
    }

    @Test
    @DisplayName("Luhn 무효 번호는 미통과")
    void luhnInvalid() {
        assertFalse(luhn.isValid("4999123456789012"));
        assertFalse(luhn.isValid("1234"));
        assertFalse(luhn.isValid(null));
    }

    @Test
    @DisplayName("등록소는 이름으로 검증기를 찾고, 모르는 이름에는 null을 준다")
    void registryLookup() {
        assertNotNull(Validators.find("SSN_CHECKSUM"));
        assertNotNull(Validators.find("LUHN"));
        assertNull(Validators.find("UNKNOWN"), "규칙 설정 오류가 검사 전체를 막아서는 안 된다");
        assertNull(Validators.find(null));
    }
}
