package im.tlgxy.cloud.module.storage.support;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.SystemException;
import im.tlgxy.cloud.core.exception.status.NotFoundAccessException;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 115网盘文件和路径ID缓存服务.
 * <p>
 * 该类负责管理115网盘存储源中文件路径与115平台内部ID之间的映射关系。
 * 由于115网盘的API操作（如获取文件列表、下载文件等）需要使用文件或文件夹的内部ID，
 * 而非文件路径，因此需要维护路径到ID的映射缓存，避免每次操作都重新查询API。
 * </p>
 * <p>
 * 每个存储源实例持有一个此类的实例，通过 {@link ConcurrentHashMap} 实现线程安全的缓存，
 * 确保不同存储源之间的缓存数据相互隔离。
 * </p>
 * <p>
 * 缓存策略：
 * <ul>
 *   <li>首次查询某个路径的ID时，会通过API拉取其父目录下的所有文件和文件夹信息并缓存</li>
 *   <li>缓存分为两个Map：pathIdMap（文件夹路径到ID）和 fileIdMap（文件路径到ID）</li>
 *   <li>根路径 "/" 和空路径 "" 的ID预设为 "0"</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
public class Open115IdCacheService {

    /**
     * 分页查询文件列表时每页的最大条数限制.
     * 值为1150，取自115网盘的品牌数字。
     */
    private static final Integer FILE_LIST_LIMIT = 1150;

    /**
     * 115网盘API返回的文件类型标识：文件.
     * 用于区分API返回的条目是文件还是文件夹。
     */
    private static final String FC_FILE = "1";

    /**
     * 文件夹路径到115平台内部ID的映射缓存.
     * <p>
     * Key: 文件夹的完整路径（去除末尾斜杠）
     * Value: 115平台中该文件夹的内部ID（cid）
     * 使用 {@link ConcurrentHashMap} 保证多线程环境下的读写安全。
     * 初始化时预设根路径 "/" 和空路径 "" 的ID为 "0"。
     * </p>
     */
    private final Map<String, String> pathIdMap = new ConcurrentHashMap<>() {{
        put("/", "0");
        put("", "0");
    }};

    /**
     * 文件路径到115平台内部ID的映射缓存.
     * <p>
     * Key: 文件的完整路径
     * Value: 115平台中该文件的内部ID（fid）
     * 使用 {@link ConcurrentHashMap} 保证多线程环境下的读写安全。
     * </p>
     */
    private final Map<String, String> fileIdMap = new ConcurrentHashMap<>();

    /**
     * 发送带认证信息的GET请求的函数接口.
     * <p>
     * 该函数由外部注入，封装了115网盘API的认证逻辑和HTTP请求细节。
     * 参数1: 请求的URL地址
     * 参数2: 请求参数（以Map形式传递）
     * 返回值: 115网盘API返回的JSON响应对象
     * </p>
     */
    private final BiFunction<String, Map<String, Object>, JSONObject> sendGetRequestWithAuth;

    /**
     * 构造函数，注入发送认证请求的函数.
     *
     * @param sendGetRequestWithAuth 用于发送带认证GET请求的函数接口
     */
    public Open115IdCacheService(BiFunction<String, Map<String, Object>, JSONObject> sendGetRequestWithAuth) {
        this.sendGetRequestWithAuth = sendGetRequestWithAuth;
    }

    /**
     * 根据文件完整路径获取文件ID.
     * <p>
     * 首先从缓存中查找，如果缓存未命中，则通过API获取父目录下的所有文件列表来填充缓存，
     * 然后再次从缓存中查找。如果仍然未找到且 throwIfNotFound 为 true，则抛出异常。
     * </p>
     *
     * @param fullPath        文件完整路径
     * @param throwIfNotFound 如果未找到文件ID，是否抛出 {@link NotFoundAccessException} 异常
     * @return 文件在115平台中的内部ID（fid），如果未找到且 throwIfNotFound 为 false 则返回 null
     */
    public String getFileId(String fullPath, boolean throwIfNotFound) {
        // 先从缓存中查找文件ID
        String id = fileIdMap.get(fullPath);
        if (id != null) {
            return id;
        }

        // 解析父目录路径
        String parentPath = FileUtils.getParentPath(fullPath);
        if (parentPath == null) {
            throw new SystemException("无法解析路径 '" + fullPath + "' 的父路径。");
        }

        // 通过API拉取父目录内容并填充缓存
        cachePathAndFileId(parentPath);

        // 再次从缓存中查找
        id = fileIdMap.get(fullPath);
        if (id == null && throwIfNotFound) {
            throw new NotFoundAccessException(ErrorCode.BIZ_FILE_NOT_EXIST);
        }
        return id;
    }

    /**
     * 根据文件夹完整路径获取文件夹（路径）ID.
     * <p>
     * 首先从缓存中查找（查找前会去除路径末尾的斜杠），如果缓存未命中，
     * 则通过API获取父目录下的所有内容来填充缓存，然后再次从缓存中查找。
     * 如果仍然未找到且 throwIfNotFound 为 true，则抛出异常。
     * </p>
     *
     * @param fullPath        文件夹完整路径
     * @param throwIfNotFound 如果未找到路径ID，是否抛出 {@link NotFoundAccessException} 异常
     * @return 文件夹在115平台中的内部ID（cid），如果未找到且 throwIfNotFound 为 false 则返回 null
     */
    public String getPathId(String fullPath, boolean throwIfNotFound) {
        // 去除路径末尾的斜杠，统一路径格式
        String trimEndSlashes = StringUtils.trimEndSlashes(fullPath);
        // 先从缓存中查找
        String id = pathIdMap.get(trimEndSlashes);
        if (id != null) {
            return id;
        }

        // 解析父目录路径
        String parentPath = FileUtils.getParentPath(trimEndSlashes);
        if (parentPath == null) {
            throw new SystemException("无法解析路径 '" + trimEndSlashes + "' 的父路径。");
        }

        // 通过API拉取父目录内容并填充缓存
        cachePathAndFileId(parentPath);

        // 再次从缓存中查找
        id = pathIdMap.get(trimEndSlashes);
        if (id == null && throwIfNotFound) {
            throw new NotFoundAccessException(ErrorCode.BIZ_FILE_NOT_EXIST);
        }
        return id;
    }

    /**
     * 通过115网盘API拉取指定文件夹下的所有内容，并将其ID缓存到本地Map中.
     * <p>
     * 该方法会分页获取文件夹下的所有文件和子文件夹，每页最多 {@link #FILE_LIST_LIMIT} 条记录。
     * 对于每个返回的条目，根据其类型（fc字段）分别存入 fileIdMap（文件）或 pathIdMap（文件夹）。
     * </p>
     * <p>
     * 注意：该方法内部通过递归调用 {@link #getPathId(String, boolean)} 获取目标文件夹的ID，
     * 如果目标文件夹本身不在缓存中，会触发链式的父目录查询。
     * </p>
     *
     * @param folderPath 要缓存的文件夹路径
     * @throws NotFoundAccessException 如果文件夹不存在或API返回的cid与请求不匹配
     */
    private void cachePathAndFileId(String folderPath) {
        // 获取目标文件夹的115平台内部ID
        String pathId = getPathId(folderPath, true);
        // 用于记录已获取的文件/文件夹ID列表，判断是否还需要继续分页
        List<String> idList = new ArrayList<>();

        int offset = 0;
        int count;
        do {
            // 调用115网盘API获取文件列表，使用cid、offset、limit进行分页查询
            JSONObject jsonObject = sendGetRequestWithAuth.apply(
                    "https://proapi.115.com/open/ufile/files",
                    new JSONObject()
                            .fluentPut("cid", pathId)
                            .fluentPut("offset", offset)
                            .fluentPut("limit", FILE_LIST_LIMIT)
                            .fluentPut("show_dir", 1));

            // 校验返回的cid是否与请求一致，防止因路径不存在被115网盘重定向到根目录
            String cid = jsonObject.getString("cid");
            if (!Objects.equals(pathId, cid)) {
                log.warn("请求的路径 ID '{}' 与返回的路径 ID '{}' 不符, 可能是 115 做了兼容处理, 返回了根目录.", pathId, cid);
                throw new NotFoundAccessException(ErrorCode.BIZ_FILE_NOT_EXIST);
            }

            // 遍历当前页的所有条目
            JSONArray fileList = jsonObject.getJSONArray("data");
            for (int i = 0; i < fileList.size(); i++) {
                JSONObject fileItem = fileList.getJSONObject(i);

                // 获取文件/文件夹的ID和名称
                String fid = fileItem.getString("fid");
                String fn = fileItem.getString("fn");

                // 拼接完整路径
                String fullPath = StringUtils.concat(folderPath, fn);
                // 根据fc字段判断是文件还是文件夹，分别存入不同的缓存Map
                if (Objects.equals(fileItem.getString("fc"), FC_FILE)) {
                    fileIdMap.put(fullPath, fid);
                } else {
                    pathIdMap.put(fullPath, fid);
                }
                idList.add(fid);
            }

            // 获取总条目数，用于判断是否已经拉取完所有数据
            count = jsonObject.getInteger("count");
            // 偏移量递增，准备拉取下一页
            offset += FILE_LIST_LIMIT;
        } while (idList.size() < count); // 当已获取的条目数小于总数时，继续分页
    }

    /**
     * 手动将文件路径和文件ID存入缓存.
     * <p>
     * 用于在已知文件ID的情况下直接更新缓存，避免重复的API查询。
     * </p>
     *
     * @param fullPath 文件完整路径
     * @param id       文件在115平台中的内部ID（fid）
     */
    public void putFileId(String fullPath, String id) {
        fileIdMap.put(fullPath, id);
    }

    /**
     * 手动将文件夹路径和路径ID存入缓存.
     * <p>
     * 存储前会自动去除路径末尾的斜杠，保持路径格式统一。
     * 用于在已知文件夹ID的情况下直接更新缓存，避免重复的API查询。
     * </p>
     *
     * @param fullPath 文件夹完整路径
     * @param id       文件夹在115平台中的内部ID（cid）
     */
    public void putPathId(String fullPath, String id) {
        pathIdMap.put(StringUtils.trimEndSlashes(fullPath), id);
    }

    /**
     * 从缓存中删除指定文件路径的ID记录.
     *
     * @param fullPath 要删除缓存的文件完整路径
     */
    public void deleteFileId(String fullPath) {
        fileIdMap.remove(fullPath);
    }

    /**
     * 从缓存中删除指定文件夹路径的ID记录，以及其下所有子文件和子文件夹的缓存.
     * <p>
     * 删除操作包括：
     * <ul>
     *   <li>移除该文件夹路径本身的ID映射</li>
     *   <li>移除所有以该路径开头的文件夹ID映射（即子文件夹）</li>
     *   <li>移除所有以该路径开头的文件ID映射（即子文件）</li>
     * </ul>
     * 这确保了当文件夹被移动或删除后，相关的缓存数据不会造成不一致。
     * </p>
     *
     * @param fullPath 要删除缓存及其子项的文件夹完整路径
     */
    public void deletePathId(String fullPath) {
        // 统一路径格式：去除末尾斜杠
        String trimmedPath = StringUtils.trimEndSlashes(fullPath);
        // 移除文件夹本身
        pathIdMap.remove(trimmedPath);

        // 移除所有以该路径开头的子文件夹和子文件缓存
        pathIdMap.entrySet().removeIf(entry -> entry.getKey().startsWith(trimmedPath));
        fileIdMap.entrySet().removeIf(entry -> entry.getKey().startsWith(trimmedPath));
    }

    /**
     * 从缓存中移除指定文件路径的ID记录，并返回被移除的ID值.
     * <p>
     * 与 {@link #deleteFileId(String)} 不同的是，该方法会返回被删除的ID，
     * 方便调用方在删除缓存的同时获知之前的映射值。
     * </p>
     *
     * @param fullPath 文件完整路径
     * @return 被移除的文件ID，如果缓存中不存在该路径则返回 null
     */
    public String removeFileIdByPath(String fullPath) {
        return fileIdMap.remove(fullPath);
    }

}