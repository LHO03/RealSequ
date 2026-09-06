package com.docversion.storage;

import com.docversion.domain.FileContent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 로컬 파일시스템 저장소. C++ FileStorage 직역.
 * <p>resolvePath = BASE_PATH/{storageKey}. 알파 단계 기본 구현이며,
 * Nextcloud 확정 시 NextcloudStorageService 등으로 교체(StorageService만 구현하면 됨).
 */
@Component
public class LocalFileStorage implements StorageService {

    private final Path basePath;

    public LocalFileStorage(@Value("${docversion.storage.base-path:./storage}") String basePath) {
        this.basePath = Path.of(basePath);
    }

    private Path resolvePath(String storageKey) {
        return basePath.resolve(storageKey);
    }

    @Override
    public void writeFile(String storageKey, FileContent content) {
        Path path = resolvePath(storageKey);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content.data(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            // 부분 작성 파일 정리 후 예외 전파(C++의 부분 작성 제거 로직 대응)
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 정리 실패는 원인 예외를 가리지 않도록 무시
            }
            throw new StorageException("파일 쓰기 실패: " + path, e);
        }
    }

    @Override
    public FileContent readFile(String storageKey) {
        Path path = resolvePath(storageKey);
        try {
            byte[] data = Files.readAllBytes(path);
            // MIME은 저장소에 따로 보존하지 않으므로 호출부가 files_versions.mimetype를 사용.
            // 여기서는 콘텐츠 바이트만 반환(C++ readFile와 동일하게 mimeType은 비움).
            return new FileContent(data, null);
        } catch (NoSuchFileException e) {
            throw new StorageException("파일을 열 수 없음: " + path, e);
        } catch (IOException e) {
            throw new StorageException("파일 읽기 실패: " + path, e);
        }
    }

    @Override
    public void deleteFile(String storageKey) {
        Path path = resolvePath(storageKey);
        try {
            Files.deleteIfExists(path); // 없으면 조용히 무시(정상 케이스)
        } catch (IOException e) {
            throw new StorageException("파일 삭제 실패: " + path, e);
        }
    }
}
