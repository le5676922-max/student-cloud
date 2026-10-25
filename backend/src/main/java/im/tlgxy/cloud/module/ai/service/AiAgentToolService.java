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
import im.tlgxy.cloud.module.storage.service.DirectoryAccessGuard;
import im.tlgxy.cloud.module.storage.service.DirectoryMetadataProvider;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.learning.service.LearningLibraryService;
import im.tlgxy.cloud.module.ai.model.automation.Automation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 可调用的站内工具。
 *
 * <p>仅开放查询和任务预览；模型不能确认或执行写入任务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentToolService {

    private static final int MAX_DIRECTORY_ITEMS = 60;
    private static final int MAX_SEARCH_RESULTS = 30;
    private static final int MAX_SEARCH_SCANNED_ITEMS = 400;
    private static final int MAX_SEARCH_DEPTH = 3;
    private static final int MAX_NATIVE_PAGE_SIZE = 200;
    private static final int MAX_NATIVE_REQUESTS = 4;

    private final StorageSourceService storageSourceService;
    private final UserService userService;
    private final FileChain fileChain;
    private final LearningLibraryService learningLibrary;
    private final AgentTaskService tasks;
    private final ProjectKnowledgeService knowledge;
    private final DirectoryAccessGuard directoryAccessGuard;

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
            case "search_learning_materials" -> searchLearningMaterials(arguments);
            case "summarize_learning_course" -> new ToolExecution(learningLibrary.courseOverview(
                    boundedIntArgument(arguments, "courseId", 0, 1, Integer.MAX_VALUE)), "已读取本人课程的真实资料片段");
            case "list_learning_courses" -> new ToolExecution(learningLibrary.courses(), "已查询本人课程列表");
            case "search_project_knowledge" -> new ToolExecution(knowledge.search(requiredArgument(arguments, "query", "请提供检索问题"), knowledge.resolveCurrentAudience()), "已检索当前角色的项目知识库");
            case "plan_file_organization" -> previewTask(arguments, "ORGANIZE_FILES");
            case "plan_date_rename" -> previewTask(arguments, "RENAME_BY_DATE");
            case "find_duplicate_files" -> new ToolExecution(tasks.duplicates(
                    requiredArgument(arguments, "storageKey", "请选择存储源"),
                    requiredArgument(arguments, "path", "请指定目录")), "已按文件内容检查当前目录重复项");
            case "plan_learning_import" -> previewTask(arguments, "IMPORT_LEARNING");
            default -> throw new BizException("Agent 请求了不支持的工具：" + toolName);
        };
    }

    private ToolExecution previewTask(JsonNode arguments, String type) {
        var plan = tasks.preview(new Automation.Request(type,
                requiredArgument(arguments, "storageKey", "请先选择存储源"),
                requiredArgument(arguments, "path", "请明确要处理的目录"),
                arguments.path("courseId").canConvertToInt() ? arguments.path("courseId").asInt() : null,
                textArgument(arguments, "tags")));
        return new ToolExecution(plan, "已生成待确认任务，尚未执行：" + plan.title());
    }

    private ToolExecution currentUser() {
        Integer userId = TlgxyCloudAuthUtil.getCurrentUserId();
        User user = userService.getById(userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", user.getUsername());
        data.put("nickname", StringUtils.defaultIfBlank(user.getNickname(), user.getUsername()));
        data.put("role", user.getRole().getDescription());
        data.put("enabled", user.getEnable());
        return new ToolExecution(data, "已读取当前账号：" + user.getUsername());
    }

    private ToolExecution searchLearningMaterials(JsonNode arguments) {
        String query = requiredArgument(arguments, "query", "请提供课程资料检索词");
        Integer courseId = arguments != null && arguments.path("courseId").canConvertToInt()
                ? arguments.path("courseId").asInt() : null;
        var result = learningLibrary.search(query, courseId);
        return new ToolExecution(result, "检索课程资料：命中 " + result.citations().size()
                + " 个片段，跳过 " + result.skipped() + " 份失效或不可访问资料");
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
        return directoryResult(snapshot);
    }

    private ToolExecution agentCapabilities() {
        Integer userId = TlgxyCloudAuthUtil.getCurrentUserId();
        User user = userService.requireLoginUser(userId);
        List<StorageSource> sources = accessibleStorageSources();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", user.getRole().getDescription());
        data.put("accessibleStorageCount", sources.size());
        data.put("readActions", List.of("查询可用存储源", "浏览目录", "搜索文件", "统计目录", "查看文件详情", "检查本地目录重复文件", "检索课程资料并提供片段引用"));
        data.put("confirmedActions", List.of("通过页面确认后上传文件", "预览并确认本地文件分类整理", "预览并确认本地文件按日期重命名", "预览并确认本地资料批量归档到本人课程", "创建每周规则，到点生成待确认任务"));
        data.put("blockedDirectActions", List.of("未确认的移动、重命名或索引", "覆盖同名文件", "删除", "复制", "创建分享", "修改权限或系统设置"));
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
        data.put("total", snapshot.complete() ? snapshot.files().size() : null);
        addScope(data, snapshot);
        data.put("statisticsScope", snapshot.complete() ? "FULL_DIRECTORY" : "SCANNED_ITEMS_ONLY");
        return new ToolExecution(data, "已统计 " + snapshot.source().getName() + snapshot.path()
                + (snapshot.complete() ? "" : " 的已扫描部分；数量、大小及最大/最近文件均不是全目录统计"));
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
        ScanBudget budget = new ScanBudget();

        while (!pending.isEmpty() && matches.size() < MAX_SEARCH_RESULTS && !limitReached) {
            SearchDirectory current = pending.removeFirst();
            DirectorySnapshot snapshot;
            try {
                snapshot = readDirectory(storageKey, current.path(), "name", "asc", budget);
            } catch (BizException e) {
                if (current.depth() == 0 || e instanceof NativeDirectoryReadException) {
                    throw e;
                }
                skippedDirectories++;
                continue;
            }
            scannedDirectories++;
            if (!snapshot.complete()) limitReached = true;

            for (FileItemResult file : snapshot.files()) {
                if (scannedItems >= MAX_SEARCH_SCANNED_ITEMS) {
                    limitReached = true;
                    break;
                }
                scannedItems++;
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

        verifyScanAuthorization(budget);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storageKey", storageKey);
        data.put("rootPath", rootPath);
        data.put("keyword", keyword);
        data.put("maxDepth", maxDepth);
        data.put("matchCount", matches.size());
        data.put("scannedItems", scannedItems);
        data.put("providerScannedItems", budget.scannedItems);
        data.put("providerRequests", budget.requests);
        data.put("scannedDirectories", scannedDirectories);
        data.put("skippedDirectories", skippedDirectories);
        boolean incomplete = limitReached || !pending.isEmpty() || skippedDirectories > 0;
        data.put("truncated", incomplete);
        data.put("complete", !incomplete);
        data.put("scope", incomplete ? "SCANNED_ITEMS_ONLY" : "REQUESTED_DEPTH");
        data.put("total", incomplete ? null : matches.size());
        data.put("observedCount", scannedItems);
        data.put("sortScope", "PER_SCANNED_DIRECTORY");
        data.put("ioBounded", budget.legacyDirectoryReads == 0);
        if (budget.legacyDirectoryReads > 0) data.put("scanLimitNotice", legacyEnumerationNotice());
        data.put("matches", matches);
        return new ToolExecution(data, "已搜索“" + keyword + "”，找到 " + matches.size() + " 项"
                + (incomplete ? "；仅搜索有界扫描范围或部分目录不可访问，未覆盖全部目录" : ""));
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
                .orElseThrow(() -> new BizException(snapshot.complete()
                        ? "文件不存在，或当前账号无权查看该文件"
                        : "本次扫描范围内未找到该文件，请缩小目录后重试；不能据此判断文件不存在"));

        Map<String, Object> data = new LinkedHashMap<>(fileItem(parentPath, item));
        data.put("storageName", snapshot.source().getName());
        data.put("storageKey", snapshot.source().getKey());
        return new ToolExecution(data, "已读取文件详情：" + item.getName());
    }

    private DirectorySnapshot readDirectory(String storageKey,
                                            String path,
                                            String orderBy,
                                            String orderDirection) {
        return readDirectory(storageKey, path, orderBy, orderDirection, new ScanBudget());
    }

    private DirectorySnapshot readDirectory(String storageKey, String path, String orderBy,
                                            String orderDirection, ScanBudget budget) {
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
            if (fileService instanceof DirectoryMetadataProvider provider && provider.supportsNativePagination()) {
                return readNativeDirectory(source, storageId, fileService, provider, request, budget);
            }
            // Legacy drivers have no bounded I/O API. Result caps do not bound their underlying enumeration.
            budget.legacyDirectoryReads++;
            List<FileItemResult> files = fileService.fileList(request.getPath());
            FileContext context = directoryContext(storageId, fileService, request, files);
            fileChain.execute(context);
            return new DirectorySnapshot(source, request.getPath(), context.getFileItemList(), true, files.size(), 0, false);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Agent 读取目录失败，storageKey={}, path={}", storageKey, normalizedPath, e);
            throw new BizException("读取目录失败，请确认存储源已启用且连接正常");
        }
    }

    /** Metadata only. Never fall back to fileList(), which can enumerate/sign an entire remote directory. */
    private DirectorySnapshot readNativeDirectory(StorageSource source, int storageId,
            AbstractBaseFileService<?> fileService, DirectoryMetadataProvider provider,
            FileListRequest request, ScanBudget budget) throws Exception {
        int actor = TlgxyCloudAuthUtil.getCurrentUserId();
        var grant = directoryAccessGuard.require(actor, storageId, fileService.getCurrentUserBasePath());
        if (budget.authorization == null) {
            budget.authorization = new ScanAuthorization(grant, actor, storageId, fileService);
        } else if (!budget.authorization.grant().equals(grant)) {
            throw new BizException("目录授权已变化，请刷新后重试");
        }
        verifyScanAuthorization(budget);
        List<FileItemResult> files = new ArrayList<>();
        String token = null;
        Set<String> tokens = new HashSet<>();
        boolean complete = false;
        int beforeItems = budget.scannedItems, beforeRequests = budget.requests;
        do {
            // Check directory/password restrictions before any provider I/O, even for empty pages.
            fileChain.execute(directoryContext(storageId, fileService, request, List.of()));
            requireSameGrant(grant, actor, storageId, fileService);
            int pageSize = Math.min(MAX_NATIVE_PAGE_SIZE, MAX_SEARCH_SCANNED_ITEMS - budget.scannedItems);
            if (pageSize <= 0 || budget.requests >= MAX_NATIVE_REQUESTS) break;
            budget.requests++;
            DirectoryMetadataProvider.NativePage page;
            try {
                page = provider.nativeDirectoryPage(request.getPath(), grant.root(), pageSize, token);
            } catch (Exception failure) {
                // A failed remote call has unknown scan cost: abort rather than spend more of this budget.
                throw new NativeDirectoryReadException("存储源分页读取失败，请稍后重试", failure);
            }
            if (page == null || page.rawItemCount() < page.files().size() || page.rawItemCount() > pageSize)
                throw new NativeDirectoryReadException("存储源分页响应异常，请稍后重试", null);
            budget.scannedItems += page.rawItemCount();
            files.addAll(page.files());
            token = page.nextProviderToken();
            if (token != null && (token.isBlank() || token.length() > 8192 || !tokens.add(token)))
                throw new NativeDirectoryReadException("存储源分页游标异常，请稍后重试", null);
            complete = token == null;
        } while (!complete);
        FileContext context = directoryContext(storageId, fileService, request, files);
        fileChain.execute(context); // Current hidden/access/password rules and requested sort on the scanned subset.
        requireSameGrant(grant, actor, storageId, fileService);
        return new DirectorySnapshot(source, request.getPath(), context.getFileItemList(), complete,
                budget.scannedItems - beforeItems, budget.requests - beforeRequests, true);
    }

    private void requireSameGrant(DirectoryAccessGuard.Grant expected, int actor, int storageId,
                                  AbstractBaseFileService<?> fileService) {
        if (!Objects.equals(actor, TlgxyCloudAuthUtil.getCurrentUserId())
                || !expected.equals(directoryAccessGuard.require(actor, storageId, fileService.getCurrentUserBasePath())))
            throw new BizException("目录授权已变化，请刷新后重试");
    }

    private void verifyScanAuthorization(ScanBudget budget) {
        ScanAuthorization authorization = budget.authorization;
        if (authorization != null) requireSameGrant(authorization.grant(), authorization.actor(),
                authorization.storageId(), authorization.fileService());
    }

    private FileContext directoryContext(int storageId, AbstractBaseFileService<?> fileService,
                                         FileListRequest request, List<FileItemResult> files) {
        return FileContext.builder().storageId(storageId).fileListRequest(request).fileItemList(files)
                .fileService(fileService).operatorUserId(TlgxyCloudAuthUtil.getCurrentUserId()).build();
    }

    private void addScope(Map<String, Object> data, DirectorySnapshot snapshot) {
        data.put("complete", snapshot.complete());
        data.put("scope", snapshot.complete() ? "FULL_DIRECTORY" : "SCANNED_ITEMS_ONLY");
        data.put("observedCount", snapshot.files().size());
        data.put("scannedItems", snapshot.scannedItems());
        data.put("providerRequests", snapshot.providerRequests());
        data.put("sortScope", snapshot.complete() ? "FULL_DIRECTORY" : "SCANNED_ITEMS_ONLY");
        data.put("ioBounded", snapshot.nativePagination());
        data.put("scanMode", snapshot.nativePagination() ? "NATIVE_BOUNDED" : "LEGACY_FULL_ENUMERATION");
        if (!snapshot.nativePagination()) data.put("scanLimitNotice", legacyEnumerationNotice());
    }

    private String legacyEnumerationNotice() {
        return "此存储源不支持原生分页，底层读取仍可能完整枚举目录；返回或搜索结果上限不代表远程请求量有界";
    }

    private ToolExecution directoryResult(DirectorySnapshot snapshot) {
        StorageSource source = snapshot.source();
        String path = snapshot.path();
        List<FileItemResult> files = snapshot.files();
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
        data.put("total", snapshot.complete() ? files.size() : null);
        data.put("truncated", !snapshot.complete() || files.size() > MAX_DIRECTORY_ITEMS);
        addScope(data, snapshot);
        data.put("items", items);
        return new ToolExecution(data, "已读取 " + source.getName() + path + (snapshot.complete()
                ? "，共 " + files.size() + " 项" : " 的部分目录；仅排序已扫描内容，总数未知"));
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

    private record DirectorySnapshot(StorageSource source, String path, List<FileItemResult> files,
                                     boolean complete, int scannedItems, int providerRequests, boolean nativePagination) {
    }

    private static final class ScanBudget {
        private int scannedItems;
        private int requests;
        private int legacyDirectoryReads;
        private ScanAuthorization authorization;
    }

    private record ScanAuthorization(DirectoryAccessGuard.Grant grant, int actor, int storageId,
                                     AbstractBaseFileService<?> fileService) {
    }

    private static final class NativeDirectoryReadException extends BizException {
        private NativeDirectoryReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record SearchDirectory(String path, int depth) {
    }
}
