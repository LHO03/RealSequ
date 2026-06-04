package com.docversion.domain;

/**
 * 파일 콘텐츠. C++ {@code struct FileContent}의 직역.
 * <p>data: 원시 바이트, mimeType: MIME 타입.
 * C++의 {@code size_t size()}는 byte[] 길이로 대체.
 */
public record FileContent(byte[] data, String mimeType) {

    public FileContent {
        if (data == null) data = new byte[0];
        if (mimeType == null) mimeType = "application/octet-stream";
    }

    /** C++ FileContent::size() 대응. */
    public long size() {
        return data.length;
    }

    public static FileContent ofText(String text, String mimeType) {
        return new FileContent(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), mimeType);
    }
}
