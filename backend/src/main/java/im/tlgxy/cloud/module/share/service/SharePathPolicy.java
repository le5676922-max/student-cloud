package im.tlgxy.cloud.module.share.service;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.share.model.dto.ShareEntryDTO;
import im.tlgxy.cloud.module.share.model.enums.ShareEntryTypeEnum;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Share paths are already decoded API values, never URLs or filesystem absolute paths. */
public final class SharePathPolicy {
    private static final Pattern ENCODED_ESCAPE = Pattern.compile("(?i)%(?:2e|2f|5c|00|25)");

    private SharePathPolicy() { }

    public static String normalize(String value) {
        if (value == null || value.isEmpty()) return "/";
        if (value.indexOf('\\') >= 0 || value.contains("://") || value.matches("(?i)^/?[a-z]:/.*")
                || value.codePoints().anyMatch(Character::isISOControl)
                || ENCODED_ESCAPE.matcher(value).find()) illegal();
        List<String> parts = Arrays.stream(value.split("/", -1)).filter(part -> !part.isEmpty()).toList();
        if (parts.stream().anyMatch(part -> part.equals(".") || part.equals(".."))) illegal();
        return "/" + String.join("/", parts);
    }

    public static boolean isWithin(String path, String base) {
        return "/".equals(base) || path.equals(base) || path.startsWith(base + "/");
    }

    static void requireSelected(List<ShareEntryDTO> entries, String path, boolean rootListing) {
        if (entries.isEmpty() || (rootListing && "/".equals(path))) return;
        boolean allowed = entries.stream().anyMatch(entry -> {
            String selected = "/" + entry.getName();
            return path.equals(selected) || (entry.getType() == ShareEntryTypeEnum.FOLDER && path.startsWith(selected + "/"));
        });
        if (!allowed) throw new BizException(ErrorCode.BIZ_STORAGE_SOURCE_ILLEGAL_OPERATION);
    }

    private static void illegal() { throw new BizException(ErrorCode.BIZ_FILE_PATH_ILLEGAL); }
}
