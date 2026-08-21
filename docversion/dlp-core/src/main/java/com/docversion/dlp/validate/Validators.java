package com.docversion.dlp.validate;

import java.util.HashMap;
import java.util.Map;

/**
 * 검증기 등록소. 이름으로 검증기를 찾는다. (RD-SRS-5.4)
 *
 * <p>규칙(dlp_patterns.validator)은 검증기를 이름 문자열로 지목한다.
 * 알 수 없는 이름이 지정된 경우 예외를 던지지 않고 null을 반환하며,
 * 호출부는 검증기가 없는 규칙과 동일하게 취급한다. 규칙 하나의 설정 오류가
 * 검사 전체를 중단시켜서는 안 되기 때문이다.
 */
public final class Validators {

    private static final Map<String, Validator> REGISTRY = new HashMap<>();

    static {
        register(new SsnChecksumValidator());
        register(new LuhnValidator());
    }

    private Validators() {
    }

    private static void register(Validator v) {
        REGISTRY.put(v.name(), v);
    }

    /**
     * 이름으로 검증기를 찾는다. 없으면 null.
     *
     * @param name dlp_patterns.validator 값. null 허용
     */
    public static Validator find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return REGISTRY.get(name);
    }
}
