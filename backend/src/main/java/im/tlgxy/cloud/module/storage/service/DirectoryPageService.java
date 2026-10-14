package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileInfoResult;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Pages only the freshly authorized, filtered and deterministically sorted list. No permission/data cache. */
public final class DirectoryPageService {
    private DirectoryPageService() { }

    public static FileInfoResult page(List<FileItemResult> authorized, String passwordPattern,
                                      FileListRequest request, int userId, String userRoot) {
        String query = request.getNameQuery() == null ? "" : request.getNameQuery().trim().toLowerCase(Locale.ROOT);
        if (query.length() > 200) throw new BizException(ErrorCode.BIZ_BAD_REQUEST);
        List<FileItemResult> matching = query.isEmpty() ? authorized : authorized.stream()
                .filter(file -> file.getName() != null && file.getName().toLowerCase(Locale.ROOT).contains(query)).toList();
        Integer size = request.getPageSize();
        if (size == null) {
            if (request.getPageCursor() != null && !request.getPageCursor().isBlank()) throw new BizException(ErrorCode.BIZ_BAD_REQUEST);
            return new FileInfoResult(matching, passwordPattern);
        }
        if (size < 1 || size > 500) throw new BizException(ErrorCode.BIZ_BAD_REQUEST);
        String revision = revision(matching, request, userId, userRoot, query);
        int offset = offset(request.getPageCursor(), revision, matching.size());
        int end = Math.min(matching.size(), offset + size);
        var result = new FileInfoResult(List.copyOf(matching.subList(offset, end)), passwordPattern);
        result.setPaged(true);
        result.setTotal(matching.size());
        result.setPageSize(size);
        result.setOffset(offset);
        if (end < matching.size()) result.setNextCursor(revision + ":" + end);
        if (offset > 0) result.setPreviousCursor(revision + ":" + Math.max(0, offset - size));
        return result;
    }

    private static int offset(String cursor, String revision, int total) {
        if (cursor == null || cursor.isBlank()) return 0;
        if (!cursor.matches("[a-f0-9]{64}:[0-9]{1,10}")) throw new BizException(ErrorCode.BIZ_BAD_REQUEST);
        if (!cursor.startsWith(revision + ":")) throw new BizException(ErrorCode.BIZ_DIRECTORY_CHANGED);
        try {
            int value = Integer.parseInt(cursor.substring(65));
            if (value < 0 || value >= total && value != 0) throw new BizException(ErrorCode.BIZ_BAD_REQUEST);
            return value;
        } catch (NumberFormatException e) { throw new BizException(ErrorCode.BIZ_BAD_REQUEST); }
    }

    private static String revision(List<FileItemResult> files, FileListRequest request, int userId, String userRoot, String query) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, userId); add(digest, userRoot); add(digest, request.getStorageKey()); add(digest, request.getPath());
            add(digest, request.getOrderBy()); add(digest, request.getOrderDirection()); add(digest, request.getPageSize()); add(digest, query);
            for (var file : files) {
                add(digest, file.getName()); add(digest, file.getPath()); add(digest, file.getType()); add(digest, file.getSize());
                add(digest, file.getTime() == null ? null : file.getTime().getTime());
                // URL signatures may change every request. Only bind whether download/preview is currently allowed.
                add(digest, file.getUrl() != null);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void add(MessageDigest digest, Object value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
