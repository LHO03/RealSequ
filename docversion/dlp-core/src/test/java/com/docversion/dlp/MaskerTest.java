package com.docversion.dlp;

import com.docversion.dlp.mask.Masker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 마스킹 시험. (RD-SRS-5.4) */
class MaskerTest {

    @Test
    @DisplayName("앞자리만 남기고 가린다")
    void keepPrefixOnly() {
        assertEquals("010203-*******", Masker.mask("010203-4567890", 7, 0));
    }

    @Test
    @DisplayName("앞뒤를 남기고 가운데를 가린다")
    void keepBothEnds() {
        assertEquals("4539***********1486", Masker.mask("4539-5787-6362-1486", 4, 4));
    }

    @Test
    @DisplayName("보존 길이가 0이면 전부 가린다")
    void keepNothing() {
        assertEquals("*************", Masker.mask("123-45-678901", 0, 0));
    }

    @Test
    @DisplayName("보존 길이 합이 원문보다 크면 전부 가린다")
    void keepLengthExceedsInput() {
        assertEquals("*****", Masker.mask("12345", 10, 10),
                "규칙 설정 실수로 원문이 그대로 새어서는 안 된다");
    }

    @Test
    @DisplayName("빈 값과 null을 안전하게 처리한다")
    void emptyInput() {
        assertEquals("", Masker.mask("", 3, 3));
        assertEquals("", Masker.mask(null, 3, 3));
    }
}
