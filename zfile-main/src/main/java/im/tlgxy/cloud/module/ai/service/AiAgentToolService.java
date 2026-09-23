package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.chain.FileChain;
import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 可调用的站内工具。
 *
 * <p>这里只开放只读能力，并始终使用当前登录用户的权限上下文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentToolService {

    private static final int MAX_DIRECTORY_ITEMS = 60;
    private static final int MAX_SEARCH_RESULTS = 30;
    private static final int MAX_SEARCH_SCANNED_ITEMS = 400;
    private static final int MAX_SEARCH_DEPTH = 3;

    private final StorageSourceService storageSourceService;
    private final UserService userService;
    private final FileChain fileChain;

    /** 根据模型给出的工具名称执行白名单内的站内查询。 */
    public ToolExecution execute(String toolName, JsonNode arguments) {
        requireEnabledCurrentUser();
        return switch (toolName) {
            case "get_current_user" -> currentUser();
            case "get_agent_capabilities" -> agentCapabilities();
            case "list_storage_sources" -> storageSources();
            case "list_directory" -> listDirectory(arguments);
            case "summarize_directory" -> summarizeDirectory(arguments);
            case "find_files" -> findFiles(arguments);
            case "get_file_details" -> fileDetails(arguments);
            default -> throw new BizException("Agent 请求了不支持的工具：" + toolName);
        };
    }

    private ToolExecution currentUser() {
        Integer userId = TlgxyCloudAuthUtil.getCurrentUserId();
        User user = userService.getById(userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", user.getUsername());
        data.put("nickname", StringUtils.defaultIfBlank(user.getNickname(), user.getUsername()));
        data.put("role", userService.isAdmin(userId) ? "管理员" : "普通用户");
        data.put("enabled", user.getEnable());
        return new ToolExecution(data, "已读取当前账号：" + user.getUsername());
    }

    private ToolExecution storageSources() {
        List<StorageSource> sources = accessibleStorageSources();
        List<Map<String, Object>> items = sources.stream().map(source -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", source.getName());
            item.put("key", source.getKey());
            item.put("type", source.getType());
            item.put("searchEnabled", source.getSearchEnable());
            return item;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", items.size());
        data.put("storageSources", items);
        return new ToolExecution(data, "已查询到 " + items.size() + " 个可用存储源");
    }

    private ToolExecution listDirectory(JsonNode arguments) {
        DirectorySnapshot snapshot = readDirectory(
                textArgument(arguments, "storageKey"),
                textArgument(arguments, "path"),
                textArgument(arguments, "orderBy"),
                textArgument(arguments, "orderDirection")
        );
        return directoryResult(snapshot.source(), snapshot.path(), snapshot.files());
    }

    private ToolExecution agentCapabilities() {
        Integer userId = TlgxyCloudAuthUtil.getCurrentUserId();
        boolean admin = userService.isAdmin(userId);
        List<StorageSource> sources = accessibleStorageSources();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", admin ? "管理员" : "普通用户");
        data.put("accessibleStorageCount", sources.size());
        data.put("readActions", List.of("查询可用存储源", "浏览目录", "搜索文件", "统计目录", "查看文件详情"));
        data.put("confirmedActions", List.of("通过页面确认后上传文件"));
        data.put("blockedDirectActions", List.of("删除", "移动", "复制", "重命名", "创建分享", "修改权限或系统设置"));
        data.put("permissionRule", "所有读取均按当前登录用户权限执行，不能切换身份或绕过目录限制");
        return new ToolExecution(data, "已确认小芽当前可用的 Agent 能力");
    }

    private ToolExecution summarizeDirectory(JsonNode arguments) {
        DirectorySnapshot snapshot = readDirectory(
                textArgument(arguments, "storageKey"),
                textArgument(arguments, "path"),
                "name",
                "asc"
        );

        long folderCount = snapshot.files().stream().filter(this::isFolder).count();
        List<FileItemResult> regularFiles = snapshot.files().stream().filter(this::isFile).toList();
        long totalBytes = regularFiles.stream()
                .map(FileItemResult::getSize)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();

        Map<String, Long> typeCounts = new LinkedHashMap<>();
        regularFiles.stream()
                .map(file -> extensionOf(file.getName()))
                .collect(java.util.stream.Collectors.groupingBy(
                        extension -> extension,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> typeCounts.put(entry.getKey(), entry.getValue()));

        List<Map<String, Object>> largestFiles = regularFiles.stream()
                .sorted(Comparator.comparing(
                        FileItemResult::getSize,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(file -> fileItem(snapshot.path(), file))
                .toList();

        List<Map<String, Object>> recentItems = snapshot.files().stream()
                .filter(file -> file.getTime() != null)
                .sorted(Comparator.comparing(FileItemResult::getTime).reversed())
                .limit(5)
                .map(file -> fileItem(snapshot.path(), file))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storageName", snapshot.source().getName());
        data.put("storageKey", snapshot.source().getKey());
        data.put("path", snapshot.path());
        data.put("fileCount", regularFiles.size());
        data.put("folderCount", folderCount);
        data.put("totalFileBytes", totalBytes);
        data.put("typeCounts", typeCounts);
        data.put("largestFiles", largestFiles);
        data.put("recentItems", recentItems);
        return new ToolExecution(data, "已统计 " + snapshot.source().getName() + snapshot.path());
    }

    private ToolExecution findFiles(JsonNode arguments) {
        String storageKey = requiredArgument(arguments, "storageKey", "搜索文件时必须提供存储源 key");
        String keyword = requiredArgument(arguments, "keyword", "搜索文件时必须提供关键词").trim();
        if (keyword.length() > 100) {
            throw new BizException("搜索关键词不能超过 100 个字符");
        }

        String rootPath = normalizeDirectoryPath(textArgument(arguments, "path"));
        int maxDepth = boundedIntArgument(arguments, "maxDepth", 2, 0, MAX_SEARCH_DEPTH);
        String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
        Deque<SearchDirectory> pending = new ArrayDeque<>();
        pending.add(new SearchDirectory(rootPath, 0));

        List<Map<String, Object>> matches = new ArrayList<>();
        int scannedItems = 0;
        int scannedDirectories = 0;
        int skippedDirectories = 0;
        boolean limitReached = false;

        while (!pending.isEmpty() && matches.size() < MAX_SEARCH_RESULTS && !limitReached) {
            SearchDirectory current = pending.removeFirst();
            DirectorySnapshot snapshot;
            try {
                snapshot = readDirectory(storageKey, current.path(), "name", "asc");
            } catch (BizException e) {
                if (current.depth() == 0) {
                    throw e;
                }
                skippedDirectories++;
                continue;
            }
            scannedDirectories++;

            for (FileItemResult file : snapshot.files()) {
                if (++scannedItems > MAX_SEARCH_SCANNED_ITEMS) {
                    limitReached = true;
                    break;
                }
                if (StringUtils.defaultString(file.getName()).toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
                    matches.add(fileItem(current.path(), file));
                    if (matches.size() >= MAX_SEARCH_RESULTS) {
                        limitReached = true;
                        break;
                    }
                }
                if (isFolder(file) && current.depth() < maxDepth && isSafeChildName(file.getName())) {
                    pending.addLast(new SearchDirectory(
                            joinDirectoryPath(current.path(), file.getName()),
                            current.depth() + 1
                    ));
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storageKey", storageKey);
        data.put("rootPath", rootPath);
        data.put("keyword", keyword);
        data.put("maxDepth", maxDepth);
        data.put("matchCount", matches.size());
        data.put("scannedItems", scannedItems);
        data.put("scannedDirectories", scannedDirectories);
        data.put("skippedDirectories", skippedDirectories);
        data.put("truncated", limitReached || !pending.isEmpty());
        data.put("matches", matches);
        return new ToolExecution(data, "已搜索“" + keyword + "”，找到 " + matches.size() + " 项");
    }

    private ToolExecution fileDetails(JsonNode arguments) {
        String storageKey = requiredArgument(arguments, "storageKey", "查看文件详情时必须提供存储源 key");
        String fullPath = normalizeItemPath(requiredArgument(
                arguments,
                "path",
                "查看文件详情时必须提供完整文件或文件夹路径"
        ));
        int separator = fullPath.lastIndexOf('/');
        String parentPath = separator <= 0 ? "/" : fullPath.substring(0, separator + 1);
        String itemName = fullPath.substring(separator + 1);

        DirectorySnapshot snapshot = readDirectory(storageKey, parentPath, "name", "asc");
        FileItemResult item = snapshot.files().stream()
                .filter(file -> Objects.equals(file.getName(), itemName))
                .findFirst()
                .orElseThrow(() -> new BizException("文件不存在，或当前账号无权查看该文件"));

        Map<String, Object> data = new LinkedHashMap<>(fileItem(parentPath, item));
        data.put("storageName", snapshot.source().getName());
        data.put("storageKey", snapshot.source().getKey());
        return new ToolExecution(data, "已读取文件详情：" + item.getName());
    }

    private DirectorySnapshot readDirectory(String storageKey,
                                            String path,
                                            String orderBy,
                                            String orderDirection) {
        if (StringUtils.isBlank(storageKey)) {
            throw new BizException("读取目录时必须提供存储源 key");
        }

        StorageSource source = accessibleStorageSources().stream()
                .filter(item -> Objects.equals(item.getKey(), storageKey))
                .findFirst()
                .orElseThrow(() -> new BizException("当前账号无权访问存储源：" + storageKey));
        Integer storageId = storageSourceService.findIdByKey(storageKey);
        if (storageId == null) {
            throw new BizException("存储源不存在或已被删除：" + storageKey);
        }

        String normalizedPath = normalizeDirectoryPath(path);
        FileListRequest request = new FileListRequest();
        request.setStorageKey(storageKey);
        request.setPath(normalizedPath);
        request.setOrderBy(validOption(orderBy, List.of("name", "size", "time"), "name"));
        request.setOrderDirection(validOption(orderDirection, List.of("asc", "desc"), "asc"));
        request.handleDefaultValue();

        try {
            AbstractBaseFileService<?> fileService = StorageSourceContext.getByStorageId(storageId);
            List<FileItemResult> files = fileService.fileList(request.getPath());
            FileContext context = FileContext.builder()
                    .storageId(storageId)
                    .fileListRequest(request)
                    .fileItemList(files)
                    .fileService(fileService)
                    .operatorUserId(TlgxyCloudAuthUtil.getCurrentUserId())
                    .build();
            fileChain.execute(context);
            return new DirectorySnapshot(source, request.getPath(), context.getFileItemList());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Agent 读取目录失败，storageKey={}, path={}", storageKey, normalizedPath, e);
            throw new BizException("读取目录失败，请确认存储源已启用且连接正常");
        }
    }

    private ToolExecution directoryResult(StorageSource source, String path, List<FileItemResult> files) {
        List<Map<String, Object>> items = new ArrayList<>();
        int limit = Math.min(files.size(), MAX_DIRECTORY_ITEMS);
        for (int i = 0; i < limit; i++) {
            FileItemResult file = files.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", file.getName());
            item.put("type", file.getType());
            item.put("size", file.getSize());
            item.put("modifiedAt", file.getTime());
            item.put("path", joinItemPath(path, file.getName()));
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storageName", source.getName());
        data.put("storageKey", source.getKey());
        data.put("path", path);
        data.put("total", files.size());
        data.put("truncated", files.size() > MAX_DIRECTORY_ITEMS);
        data.put("items", items);
        return new ToolExecution(data, "已读取 " + source.getName() + path + "，共 " + files.size() + " 项");
    }

    private List<StorageSource> accessibleStorageSources() {
        return storageSourceService.findAllEnableOrderByOrderNum(TlgxyCloudAuthUtil.getCurrentUserId());
    }

    private void requireEnabledCurrentUser() {
        User user = userService.getById(TlgxyCloudAuthUtil.getCurrentUserId());
        if (user == null || !Boolean.TRUE.equals(user.getEnable())) {
            throw new BizException("当前账号已被禁用，无法使用 AI 助手");
        }
    }

    private String normalizePath(String path) {
        String normalized = StringUtils.defaultIfBlank(path, "/").replace('\\', '/');
        if (normalized.length() > 500 || normalized.indexOf('\0') >= 0) {
            throw new BizException("目录路径不合法");
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new BizException("目录路径不能包含上级跳转");
            }
        }
        return normalized;
    }

    private String normalizeDirectoryPath(String path) {
        String normalized = normalizePath(path);
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private String normalizeItemPath(String path) {
        String normalized = normalizePath(path);
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if ("/".equals(normalized)) {
            throw new BizException("请提供具体文件或文件夹路径，不能只填写根目录");
        }
        return normalized;
    }

    private String joinDirectoryPath(String parentPath, String childName) {
        return normalizeDirectoryPath(parentPath) + childName + "/";
    }

    private String joinItemPath(String parentPath, String itemName) {
        return normalizeDirectoryPath(parentPath) + itemName;
    }

    private Map<String, Object> fileItem(String parentPath, FileItemResult file) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", file.getName());
        item.put("path", joinItemPath(parentPath, file.getName()));
        item.put("type", file.getType());
        item.put("size", file.getSize());
        item.put("modifiedAt", file.getTime());
        return item;
    }

    private boolean isFile(FileItemResult item) {
        return FileTypeEnum.FILE.equals(item.getType());
    }

    private boolean isFolder(FileItemResult item) {
        return FileTypeEnum.FOLDER.equals(item.getType());
    }

    private boolean isSafeChildName(String name) {
        return StringUtils.isNotBlank(name)
                && !name.contains("/")
                && !name.contains("\\")
                && !".".equals(name)
                && !"..".equals(name);
    }

    private String extensionOf(String fileName) {
        String normalized = StringUtils.defaultString(fileName);
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == normalized.length() - 1) {
            return "无扩展名";
        }
        return normalized.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String requiredArgument(JsonNode arguments, String field, String errorMessage) {
        String value = textArgument(arguments, field);
        if (StringUtils.isBlank(value)) {
            throw new BizException(errorMessage);
        }
        return value;
    }

    private int boundedIntArgument(JsonNode arguments,
                                   String field,
                                   int defaultValue,
                                   int minValue,
                                   int maxValue) {
        if (arguments == null || !arguments.has(field) || !arguments.path(field).canConvertToInt()) {
            return defaultValue;
        }
        return Math.max(minValue, Math.min(arguments.path(field).asInt(), maxValue));
    }

    private String validOption(String value, List<String> options, String defaultValue) {
        return value != null && options.contains(value) ? value : defaultValue;
    }

    private String textArgument(JsonNode arguments, String field) {
        return arguments == null ? null : arguments.path(field).asText(null);
    }

    /** 工具输出及其前端摘要。 */
    public record ToolExecution(Object data, String summary) {
    }

    private record DirectorySnapshot(StorageSource source, String path, List<FileItemResult> files) {
    }

    private record SearchDirectory(String path, int depth) {
    }
}
