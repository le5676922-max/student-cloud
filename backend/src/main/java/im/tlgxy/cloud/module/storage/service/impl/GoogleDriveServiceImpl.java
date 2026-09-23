package im.tlgxy.cloud.module.storage.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.core.net.url.UrlQuery;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.http.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.core.SystemException;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.RequestHolder;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.constant.StorageConfigConstant;
import im.tlgxy.cloud.module.storage.constant.StorageSourceConnectionProperties;
import im.tlgxy.cloud.module.storage.model.bo.RefreshTokenCacheBO;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceMetadata;
import im.tlgxy.cloud.module.storage.model.dto.RefreshTokenInfoDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.GoogleDriveParam;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.oauth2.service.IOAuth2Service;
import im.tlgxy.cloud.module.storage.service.StorageSourceConfigService;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import im.tlgxy.cloud.module.storage.service.base.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Google Drive 存储服务实现类。
 * <p>
 * 继承自 {@link AbstractProxyTransferService}，通过 Google Drive API v3 与 Google Drive 进行文件交互。
 * 同时实现了 {@link RefreshTokenService} 接口，支持 OAuth2 AccessToken 的自动刷新。
 * 文件的上传和下载通过代理中转方式实现，使用 Google Drive RESTful API 进行文件操作。
 * </p>
 * <p>
 * 支持的功能包括：文件列表、文件/文件夹创建、删除、重命名、移动、复制、上传、下载等。
 * 支持个人云盘和团队共享盘（Team Drive）。
 * </p>
 * <p>
 * 作用域为 prototype，每次注入时创建新的实例，确保不同存储源的参数相互隔离。
 * </p>
 *
 * @author zhaojun
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Slf4j
public class GoogleDriveServiceImpl extends AbstractProxyTransferService<GoogleDriveParam> implements RefreshTokenService {

	/**
	 * 文件类型：文件夹
	 */
	private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";

	/**
	 * 文件类型：快捷方式
	 */
	private static final String SHORTCUT_MIME_TYPE = "application/vnd.google-apps.shortcut";

	/**
	 * 文件基础操作 API 地址
	 */
	private static final String DRIVE_FILE_URL = "https://www.googleapis.com/drive/v3/files";


	/**
	 * 文件上传操作 API 地址（multipart 上传方式）
	 */
	private static final String DRIVE_FILE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart";

	/**
	 * OAuth2 刷新 AccessToken 的 API URL
	 */
	private static final String REFRESH_TOKEN_URL = "https://oauth2.googleapis.com/token";

	/**
	 * 存储源配置服务，用于读写 Token 等配置信息
	 */
	@jakarta.annotation.Resource
	private StorageSourceConfigService storageSourceConfigService;

	/**
	 * 初始化 Google Drive 服务。
	 * <p>
	 * 如果已存在 RefreshToken 过期时间，直接将其加载到缓存中；
	 * 否则立即刷新获取新的 AccessToken。
	 * </p>
	 */
	@Override
	public void init() {
		Integer refreshTokenExpiredAt = param.getRefreshTokenExpiredAt();
		if (refreshTokenExpiredAt == null) {
			// 没有 Token 信息，需要立即刷新获取
			refreshAccessToken();
		} else {
			// 已有的 Token 信息加载到缓存中
			RefreshTokenInfoDTO tokenInfoDTO = RefreshTokenInfoDTO.success(param.getAccessToken(), param.getRefreshToken(), refreshTokenExpiredAt);
			RefreshTokenCacheBO.putRefreshTokenInfo(storageId, RefreshTokenCacheBO.RefreshTokenInfo.success(tokenInfoDTO));
		}
	}

	/**
	 * 根据路径获取文件/文件夹的 Google Drive ID。
	 * <p>
	 * 默认会拼接当前用户的基础路径。
	 * </p>
	 *
	 * @param path 文件/文件夹路径
	 * @return Google Drive 中的文件/文件夹 ID
	 */
	private String getIdByPath(String path) {
		return getIdByPath(path, true);
	}

	/**
	 * 根据路径获取文件/文件夹的 Google Drive ID。
	 * <p>
	 * 通过逐级查询 API 来解析路径中的每一级文件夹，最终返回目标路径对应的 ID。
	 * 如果路径为空或为根路径 "/"，则返回根目录 ID（默认 "root"，团队盘则为 driveId）。
	 * 支持 Google Drive 快捷方式（shortcut）的自动解析。
	 * </p>
	 *
	 * @param path                     文件/文件夹路径
	 * @param concatCurrentUserBasePath 是否拼接当前用户的基础路径
	 * @return Google Drive 中的文件/文件夹 ID
	 */
	private String getIdByPath(String path, boolean concatCurrentUserBasePath) {
		// 拼接完整路径
		String fullPath = StringUtils.concat(param.getBasePath(), concatCurrentUserBasePath ? getCurrentUserBasePath() : "", path);
		// 如果路径为空或为根路径，返回根目录 ID
		if (StringUtils.isEmpty(fullPath) || StringUtils.equals(fullPath, StringUtils.SLASH)) {
			return StringUtils.isEmpty(param.getDriveId()) ? "root" : param.getDriveId();
		}

		// 按 "/" 分割路径，逐级查找
		List<String> pathList = StringUtils.split(fullPath, StringUtils.SLASH, false, true);

		String driveId = "";
		for (String subPath : pathList) {
			// 构建查询参数：按名称和父级 ID 查找
			String folderIdParam = new GoogleDriveAPIParam().getDriveIdByPathParam(subPath, driveId);
			HttpRequest httpRequest = commonHttpRequest(HttpUtil.createGet(DRIVE_FILE_URL + "?" + folderIdParam));

			HttpResponse httpResponse = httpRequest.execute();

			checkHttpResponseIsError(httpResponse);

			String body = httpResponse.body();

			JSONObject jsonRoot = JSON.parseObject(body);
			JSONArray files = jsonRoot.getJSONArray("files");

			// 未找到文件则抛出 FileNotFoundException
			if (files.isEmpty()) {
				throw ExceptionUtil.wrapRuntime(new FileNotFoundException());
			}

			// 取最后一个匹配结果
			JSONObject jsonLastItem = files.getJSONObject(files.size() - 1);
			// 如果是快捷方式，获取目标 ID；否则直接获取 ID
			if (jsonLastItem.containsKey("shortcutDetails")) {
				driveId = jsonLastItem.getJSONObject("shortcutDetails").getString("targetId");
			} else {
				driveId = jsonLastItem.getString("id");
			}
		}

		return driveId;
	}

	/**
	 * 获取指定文件夹下的文件列表。
	 * <p>
	 * 通过 Google Drive API 分页获取指定文件夹下的所有文件和子目录，
	 * 支持分页查询，每次最多获取 1000 条记录。
	 * </p>
	 *
	 * @param folderPath 文件夹路径
	 * @return 文件/文件夹列表
	 * @throws Exception 网络或 API 异常
	 */
	@Override
	public List<FileItemResult> fileList(String folderPath) throws Exception {
		List<FileItemResult> result = new ArrayList<>();

		// 根据路径获取文件夹 ID
		String folderId = getIdByPath(folderPath);
		String pageToken = "";
		// 分页循环获取所有文件
		do {
			String folderIdParam = new GoogleDriveAPIParam().getFileListParam(folderId, pageToken);
			HttpRequest httpRequest = commonHttpRequest(HttpUtil.createGet(DRIVE_FILE_URL + "?" + folderIdParam));
			httpRequest.setConnectionTimeout(StorageSourceConnectionProperties.DEFAULT_CONNECTION_TIMEOUT_MILLIS);

			HttpResponse httpResponse = httpRequest.execute();

			checkHttpResponseIsError(httpResponse);

			String body = httpResponse.body();

			JSONObject jsonObject = JSON.parseObject(body);
			pageToken = jsonObject.getString("nextPageToken");  // 获取下一页的 token
			JSONArray files = jsonObject.getJSONArray("files");
			result.addAll(jsonArrayToFileList(files, folderPath));
		} while (StringUtils.isNotEmpty(pageToken));  // 还有下一页则继续

		return result;
	}

	/**
	 * 根据路径和文件名获取单个文件项信息。
	 * <p>
	 * 通过 Google Drive API 的 files/{fileId} 接口获取单个文件的元数据。
	 * </p>
	 *
	 * @param pathAndName 文件的完整路径和名称
	 * @return 文件项信息，未找到时返回 null
	 */
	@Override
	public FileItemResult getFileItem(String pathAndName) {
		// 根据路径获取文件 ID
		String fileId = getIdByPath(pathAndName);

		// 请求指定字段以减少数据传输量
		HttpRequest httpRequest = commonHttpRequest(HttpUtil.createGet(DRIVE_FILE_URL + StringUtils.SLASH + fileId));
		httpRequest.body("fields=id,name,mimeType,shortcutDetails,size,modifiedTime");
		HttpResponse httpResponse = httpRequest.execute();

		// 文件不存在时返回 null
		if (httpResponse.getStatus() == HttpStatus.NOT_FOUND.value()) {
			return null;
		}

		checkHttpResponseIsError(httpResponse);

		String body = httpResponse.body();
		JSONObject jsonObject = JSON.parseObject(body);
		String folderPath = FileUtils.getParentPath(pathAndName);
		return jsonObjectToFileItem(jsonObject, folderPath);
	}


	/**
	 * 在指定路径下创建新文件夹。
	 * <p>
	 * 通过 Google Drive API 创建 mimeType 为 "application/vnd.google-apps.folder" 的文件。
	 * </p>
	 *
	 * @param path 父目录路径
	 * @param name 新文件夹名称
	 * @return 创建成功返回 true
	 */
	@Override
	public boolean newFolder(String path, String name) {
		HttpResponse httpResponse = commonHttpRequest(HttpUtil.createPost(DRIVE_FILE_URL))
						.body(new JSONObject()
						.fluentPut("name", name)
						.fluentPut("mimeType", FOLDER_MIME_TYPE)
						.fluentPut("parents", Collections.singletonList(getIdByPath(path)))
						.toJSONString())
				.execute();

		checkHttpResponseIsError(httpResponse);

		return true;
	}

	/**
	 * 删除指定文件。
	 * <p>
	 * 通过 Google Drive API 的 DELETE 方法删除文件。
	 * </p>
	 *
	 * @param path 文件所在目录路径
	 * @param name 文件名
	 * @return 删除成功返回 true
	 */
	@Override
	public boolean deleteFile(String path, String name) {
		String pathAndName = StringUtils.concat(path, name);
		HttpResponse httpResponse = commonHttpRequest(HttpUtil.createRequest(Method.DELETE, DRIVE_FILE_URL + StringUtils.SLASH + getIdByPath(pathAndName)))
				.execute();

		checkHttpResponseIsError(httpResponse);

		return true;
	}

	/**
	 * 删除指定文件夹。
	 * <p>
	 * Google Drive 中删除文件夹与删除文件使用相同的 API。
	 * </p>
	 *
	 * @param path 文件夹所在目录路径
	 * @param name 文件夹名称
	 * @return 删除成功返回 true
	 */
	@Override
	public boolean deleteFolder(String path, String name) {
		return deleteFile(path, name);
	}

	/**
	 * 重命名文件。
	 * <p>
	 * 通过 Google Drive API 的 PATCH 方法更新文件的 name 属性。
	 * </p>
	 *
	 * @param path    文件所在目录路径
	 * @param name    原文件名
	 * @param newName 新文件名
	 * @return 重命名成功返回 true
	 */
	@Override
	public boolean renameFile(String path, String name, String newName) {
		String pathAndName = StringUtils.concat(path, name);
		String fileId = getIdByPath(pathAndName);

		HttpResponse httpResponse = commonHttpRequest(HttpUtil.createRequest(Method.PATCH, DRIVE_FILE_URL + StringUtils.SLASH + fileId))
				.body(new JSONObject()
						.fluentPut("name", newName)
						.toJSONString())
				.execute();

		checkHttpResponseIsError(httpResponse);

		return true;
	}

	/**
	 * 重命名文件夹。
	 * <p>
	 * Google Drive 中重命名文件夹与重命名文件使用相同的 API。
	 * </p>
	 *
	 * @param path    文件夹所在目录路径
	 * @param name    原文件夹名称
	 * @param newName 新文件夹名称
	 * @return 重命名成功返回 true
	 */
	@Override
	public boolean renameFolder(String path, String name, String newName) {
		return renameFile(path, name, newName);
	}


	/**
	 * 获取文件上传 URL。
	 * <p>
	 * Google Drive 使用代理上传来实现文件上传。
	 * </p>
	 *
	 * @param path 上传目标路径
	 * @param name 文件名
	 * @param size 文件大小
	 * @return 代理上传 URL
	 */
	@Override
	public String getUploadUrl(String path, String name, Long size) {
		return super.getProxyUploadUrl(path, name);
	}


	/**
	 * 上传文件到 Google Drive。
	 * <p>
	 * 使用 multipart/related 方式上传文件，先发送文件元数据（JSON），再发送文件二进制内容。
	 * </p>
	 *
	 * @param pathAndName 上传目标路径和文件名
	 * @param inputStream 文件输入流
	 * @param size        文件大小
	 */
	@Override
	public void uploadFile(String pathAndName, InputStream inputStream, Long size) {
		// 生成唯一的 boundary 分隔符
		String boundary = IdUtil.fastSimpleUUID();
		String fileName = FileUtils.getName(pathAndName);
		String folderName = FileUtils.getParentPath(pathAndName);

		// 构建文件元数据 JSON
		String jsonString = new JSONObject()
				.fluentPut("name", fileName)
				.fluentPut("parents", Collections.singletonList(getIdByPath(folderName)))
				.toJSONString();
		// 构建 multipart 请求体：元数据部分 + 文件内容部分
		HttpEntity entity = MultipartEntityBuilder.create()
				.setMimeSubtype("related")
				.setBoundary(boundary)
				.addTextBody(boundary, jsonString, ContentType.APPLICATION_JSON)
				.addBinaryBody(boundary, inputStream)
				.build();

		// 使用 Apache HttpClient 发送上传请求（multipart 上传需要特殊处理）
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			HttpUriRequest httpUriRequest = RequestBuilder.post(DRIVE_FILE_UPLOAD_URL)
					.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + checkExpiredAndGetAccessToken())
					.setEntity(entity)
					.build();

			CloseableHttpResponse response = httpClient.execute(httpUriRequest);
			checkHttpResponseIsError(response);
		} catch (IOException e) {
			throw ExceptionUtil.wrapRuntime(e);
		}
	}

	/**
	 * 获取文件的下载链接。
	 * <p>
	 * Google Drive 使用代理下载方式生成下载 URL。
	 * </p>
	 *
	 * @param pathAndName 文件的路径和名称
	 * @return 代理下载 URL
	 */
	@Override
	public String getDownloadUrl(String pathAndName) {
		return super.getProxyDownloadUrl(pathAndName);
	}

	/**
	 * 将文件以流的形式下载并写入 HTTP 响应。
	 * <p>
	 * 通过 Google Drive API 的 alt=media 参数获取文件的原始媒体内容，
	 * 支持断点续传（通过 Range 请求头）。使用异步 HTTP 请求以支持流式传输。
	 * </p>
	 *
	 * @param pathAndName 文件的路径和名称
	 * @return HTTP 响应实体（本方法始终返回 null，实际响应由 RequestHolder 处理）
	 */
	@Override
	public ResponseEntity<Resource> downloadToStream(String pathAndName) {
		// 根据路径获取文件 ID（不拼接用户基础路径）
		String fileId = getIdByPath(pathAndName, false);

		HttpServletRequest request = RequestHolder.getRequest();

		// 使用 alt=media 参数获取文件的原始媒体内容
		HttpRequest httpRequest = commonHttpRequest(HttpUtil.createGet(DRIVE_FILE_URL + StringUtils.SLASH + fileId));
		httpRequest.body("alt=media");
		// 转发客户端的 Range 请求头以支持断点续传
		httpRequest.header(HttpHeaders.RANGE, request.getHeader(HttpHeaders.RANGE));
		// 异步执行请求以支持流式传输
		HttpResponse httpResponse = httpRequest.executeAsync();
		checkHttpResponseIsError(httpResponse);

		// 获取响应中的 Content-Length
		String contentLengthHeader = httpResponse.header(Header.CONTENT_LENGTH.getValue());
		Long contentLength = StringUtils.isEmpty(contentLengthHeader) ? null : Convert.toLong(contentLengthHeader, null);
		// 判断是否为部分内容响应（206 Partial Content）
		boolean partialContentFromInputStream = httpResponse.getStatus() == HttpStatus.PARTIAL_CONTENT.value();
		HttpServletResponse response = RequestHolder.getResponse();
		// 如果响应中包含 Content-Encoding，传递给客户端
		String contentEncoding = httpResponse.header(Header.CONTENT_ENCODING.getValue());
		if (StringUtils.isNotEmpty(contentEncoding)) {
			response.setHeader(org.springframework.http.HttpHeaders.CONTENT_ENCODING, contentEncoding);
		}

		// 将文件流写入 HTTP 响应
		try (InputStream inputStream = httpResponse.bodyStream()) {
			String fileName = FileUtils.getName(pathAndName);
			RequestHolder.writeFile(inputStream, fileName, contentLength, partialContentFromInputStream, param.isProxyLinkForceDownload());
		} catch (IOException e) {
			throw ExceptionUtil.wrapRuntime(e);
		}
		return null;
	}

	/**
	 * 复制文件。
	 * <p>
	 * 通过 Google Drive API 的 files/{fileId}/copy 接口复制文件到目标目录。
	 * </p>
	 *
	 * @param path       源文件路径
	 * @param name       源文件名
	 * @param targetPath 目标路径
	 * @param targetName 目标文件名
	 * @return 复制成功返回 true
	 */
	@Override
	public boolean copyFile(String path, String name, String targetPath, String targetName) {
		String srcPathAndName = StringUtils.concat(path, name);
		String srcFileId = getIdByPath(srcPathAndName);

		HttpResponse httpResponse = commonHttpRequest(HttpUtil.createPost(DRIVE_FILE_URL + StringUtils.SLASH + srcFileId + "/copy"))
				.body(new JSONObject()
						.fluentPut("name", targetName)
						.fluentPut("parents", Collections.singletonList(getIdByPath(targetPath)))
						.fluentPut("supportsAllDrives", true)
						.toJSONString())
				.execute();

		checkHttpResponseIsError(httpResponse);

		return true;
	}

	/**
	 * 复制文件夹。
	 * <p>
	 * Google Drive API 不直接支持文件夹复制，因此抛出不支持操作的异常。
	 * </p>
	 *
	 * @param path       源文件夹路径
	 * @param name       源文件夹名
	 * @param targetPath 目标路径
	 * @param targetName 目标文件夹名
	 * @throws BizException 始终抛出，因为不支持此操作
	 */
	@Override
	public boolean copyFolder(String path, String name, String targetPath, String targetName) {
		throw new BizException(ErrorCode.BIZ_STORAGE_NOT_SUPPORT_OPERATION);
	}

	/**
	 * 移动文件。
	 * <p>
	 * 通过 Google Drive API 的 PATCH 方法，同时指定 addParents 和 removeParents 参数来实现文件移动。
	 * </p>
	 *
	 * @param path       源文件路径
	 * @param name       源文件名
	 * @param targetPath 目标路径
	 * @param targetName 目标文件名
	 * @return 移动成功返回 true
	 */
	@Override
	public boolean moveFile(String path, String name, String targetPath, String targetName) {
		String pathAndName = StringUtils.concat(path, name);
		String fileId = getIdByPath(pathAndName);

		// 通过 addParents 和 removeParents 实现文件的移动
		HttpResponse httpResponse = commonHttpRequest(
				HttpUtil.createRequest(Method.PATCH,
						UrlBuilder.of(DRIVE_FILE_URL + StringUtils.SLASH + fileId)
								.setQuery(UrlQuery.of(new JSONObject()
												.fluentPut("addParents", getIdByPath(targetPath))
												.fluentPut("removeParents", getIdByPath(path))
												.fluentPut("supportsAllDrives", true)
										)
								).build()
				)
		).execute();

		checkHttpResponseIsError(httpResponse);

		return true;
	}

	/**
	 * 移动文件夹。
	 * <p>
	 * Google Drive 中移动文件夹与移动文件使用相同的 API。
	 * </p>
	 *
	 * @param path       源文件夹路径
	 * @param name       源文件夹名
	 * @param targetPath 目标路径
	 * @param targetName 目标文件夹名
	 * @return 移动成功返回 true
	 */
	@Override
	public boolean moveFolder(String path, String name, String targetPath, String targetName) {
		return moveFile(path, name, targetPath, targetName);
	}

	/**
	 * 返回当前存储类型枚举。
	 *
	 * @return 存储类型为 Google Drive
	 */
	@Override
	public StorageTypeEnum getStorageTypeEnum() {
		return StorageTypeEnum.GOOGLE_DRIVE;
	}

	/**
	 * 根据 RefreshToken 刷新 AccessToken。
	 * <p>
	 * 调用 Google OAuth2 的 token 刷新接口，使用 client_id、client_secret 和 refresh_token
	 * 获取新的 AccessToken。返回包含新 Token 信息的 DTO 对象。
	 * </p>
	 *
	 * @return 刷新后的 Token 信息
	 * @throws SystemException 当 API 返回非 200 状态码时抛出
	 */
	public RefreshTokenInfoDTO getRefreshToken() {
		// 从数据库获取存储的 RefreshToken
		StorageSourceConfig refreshStorageSourceConfig =
				storageSourceConfigService.findByStorageIdAndName(storageId, StorageConfigConstant.REFRESH_TOKEN_KEY);

		// 构建 OAuth2 Token 刷新请求参数
		String paramStr = "client_id=" + param.getClientId() +
				"&client_secret=" + param.getClientSecret() +
				"&refresh_token=" + refreshStorageSourceConfig.getValue() +
				"&grant_type=refresh_token" +
				"&access_type=offline";

		if (log.isDebugEnabled()) {
			log.debug("{} 尝试刷新令牌, 请求参数: {}", getStorageSimpleInfo(), param);
		}

		// 发送 Token 刷新请求
		HttpRequest post = HttpUtil.createPost(REFRESH_TOKEN_URL + "?" + paramStr);
		post.timeout(5 * 1000);  // 5 秒超时
		HttpResponse response = post.execute();

		String responseBody = response.body();
		int responseStatus = response.getStatus();

		if (log.isDebugEnabled()) {
			log.debug("{} 刷新令牌完成. 响应状态码: {}, 响应体: {}", getStorageSimpleInfo(), responseStatus, responseBody);
		}

		// 非 200 响应则抛出异常
		if (response.getStatus() != HttpStatus.OK.value()) {
			throw new SystemException(responseBody);
		}

		// 解析响应中的 Token 信息
		JSONObject jsonBody = JSONObject.parseObject(responseBody);
		String accessToken = jsonBody.getString(IOAuth2Service.ACCESS_TOKEN_FIELD_NAME);
		String refreshToken = jsonBody.getString(IOAuth2Service.REFRESH_TOKEN_FIELD_NAME);
		Integer expiresIn = jsonBody.getInteger(IOAuth2Service.EXPIRES_IN_FIELD_NAME);
		return RefreshTokenInfoDTO.success(accessToken, refreshToken, expiresIn);
	}

	/**
	 * 刷新当前存储源的 AccessToken。
	 * <p>
	 * 调用 {@link #getRefreshToken()} 获取新的 Token，然后更新数据库中的 Token 配置
	 * 和内存缓存中的 Token 信息。刷新失败时记录失败状态。
	 * </p>
	 */
	@Override
	public void refreshAccessToken() {
		try {
			// 获取新的 Token
			RefreshTokenInfoDTO tokenInfoDTO = getRefreshToken();

			if (tokenInfoDTO.getAccessToken() == null) {
				throw new SystemException("存储源 " + storageId + " 刷新令牌失败, 获取到令牌为空.");
			}

			// 更新数据库中的 Token 配置
			StorageSourceConfig accessTokenConfig = storageSourceConfigService.findByStorageIdAndName(storageId, StorageConfigConstant.ACCESS_TOKEN_KEY);
			StorageSourceConfig refreshTokenConfig = storageSourceConfigService.findByStorageIdAndName(storageId, StorageConfigConstant.REFRESH_TOKEN_KEY);
			StorageSourceConfig refreshTokenExpiredAtConfig = storageSourceConfigService.findByStorageIdAndName(storageId, StorageConfigConstant.REFRESH_TOKEN_EXPIRED_AT_KEY);
			accessTokenConfig.setValue(tokenInfoDTO.getAccessToken());
			refreshTokenConfig.setValue(tokenInfoDTO.getRefreshToken());
			refreshTokenExpiredAtConfig.setValue(String.valueOf(tokenInfoDTO.getExpiredAt()));

			// 批量更新 Token 配置
			storageSourceConfigService.updateBatch(storageId, Arrays.asList(accessTokenConfig, refreshTokenConfig, refreshTokenExpiredAtConfig));
			// 更新内存缓存
			RefreshTokenCacheBO.putRefreshTokenInfo(storageId, RefreshTokenCacheBO.RefreshTokenInfo.success(tokenInfoDTO));
		} catch (Exception e) {
			// 刷新失败时记录失败状态到缓存
			RefreshTokenCacheBO.putRefreshTokenInfo(storageId, RefreshTokenCacheBO.RefreshTokenInfo.fail("AccessToken 刷新失败: " + e.getMessage()));
			throw new SystemException("存储源 " + storageId + " 刷新令牌失败, 获取时发生异常.", e);
		}
	}

	/**
	 * 将 Google Drive API 返回的 JSON 数组转换为 TlgxyCloud 文件对象列表。
	 *
	 * @param jsonArray  API 返回的文件 JSON 数组
	 * @param folderPath 所属文件夹路径
	 * @return TlgxyCloud 文件对象列表
	 */
	public List<FileItemResult> jsonArrayToFileList(JSONArray jsonArray, String folderPath) {
		ArrayList<FileItemResult> fileList = new ArrayList<>();

		// 遍历 JSON 数组，逐个转换为 FileItemResult
		for (int i = 0; i < jsonArray.size(); i++) {
			fileList.add(jsonObjectToFileItem(jsonArray.getJSONObject(i), folderPath));
		}

		return fileList;
	}


	/**
	 * 将 Google Drive API 返回的 JSON 对象转换为 TlgxyCloud 文件对象。
	 * <p>
	 * 处理逻辑：
	 * 1. 提取文件名、大小、修改时间等基本信息
	 * 2. 如果是快捷方式（shortcut），解析目标文件的 mimeType
	 * 3. 根据 mimeType 判断是文件夹还是文件
	 * 4. 文件类型设置下载 URL
	 * </p>
	 *
	 * @param jsonObject API 返回的文件 JSON 对象
	 * @param folderPath 所属文件夹路径
	 * @return TlgxyCloud 文件对象
	 */
	public FileItemResult jsonObjectToFileItem(JSONObject jsonObject, String folderPath) {
		FileItemResult fileItemResult = new FileItemResult();
		fileItemResult.setName(jsonObject.getString("name"));
		fileItemResult.setPath(folderPath);
		fileItemResult.setSize(jsonObject.getLong("size"));

		// 获取文件的 MIME 类型
		String mimeType = jsonObject.getString("mimeType");
		// 如果是快捷方式，需要获取目标文件的真实 MIME 类型
		if (ObjectUtil.equals(SHORTCUT_MIME_TYPE, mimeType)) {
			JSONObject shortcutDetails = jsonObject.getJSONObject("shortcutDetails");
			mimeType = shortcutDetails.getString("targetMimeType");
		}

		// 根据 MIME 类型判断是文件还是文件夹
		if (StringUtils.equals(mimeType, FOLDER_MIME_TYPE)) {
			fileItemResult.setType(FileTypeEnum.FOLDER);
		} else {
			fileItemResult.setType(FileTypeEnum.FILE);
			// 文件类型设置下载 URL
			fileItemResult.setUrl(getDownloadUrl(StringUtils.concat(getCurrentUserBasePath(), folderPath, fileItemResult.getName())));
		}

		fileItemResult.setTime(jsonObject.getDate("modifiedTime"));

		// 如果文件大小为 null，设置为 -1 表示未知
		if (fileItemResult.getSize() == null) {
			fileItemResult.setSize(-1L);
		}

		return fileItemResult;
	}


	/**
	 * Google Drive API 请求参数封装类。
	 * <p>
	 * 用于构建 Google Drive API v3 的各种请求参数，包括文件列表查询、
	 * ID 查询和搜索等场景。支持团队盘和个人云盘两种模式。
	 * 通过反射机制将字段自动转换为 URL 查询参数字符串。
	 * </p>
	 */
	@Data
	class GoogleDriveAPIParam {

		/**
		 * 默认分页大小：1000 条
		 */
		private final Integer DEFAULT_PAGE_SIZE = 1000;

		/**
		 * 存储源（团队盘）ID
		 */
		private String driveId;

		/**
		 * 是否返回共享驱动器或团队盘的内容
		 */
		private boolean includeItemsFromAllDrives;

		/**
		 * 查询适用的文件分组，支持 'user'（个人）, 'drive'（团队盘）, 'allDrives'（全部）
		 */
		private String corpora;

		/**
		 * 请求的应用程序是否同时支持"我的云端硬盘"和共享云端硬盘
		 */
		private boolean supportsAllDrives;

		/**
		 * 请求返回的字段（部分响应）
		 */
		private String fields;

		/**
		 * 查询条件（q 参数）
		 */
		private String q;

		/**
		 * 每页返回的条数
		 */
		private Integer pageSize;

		/**
		 * 下一页的分页 token
		 */
		private String pageToken;

		/**
		 * 构建根据路径获取文件夹 ID 的 API 请求参数。
		 * <p>
		 * 通过名称精确匹配和父级 ID 来查找文件夹。
		 * 自动转义路径中的单引号字符。
		 * </p>
		 *
		 * @param folderPath 文件夹路径（名称）
		 * @param parentId   父级文件夹的 Google Drive ID
		 * @return URL 查询参数字符串
		 */
		public String getDriveIdByPathParam(String folderPath, String parentId) {
			GoogleDriveAPIParam googleDriveApiParam = getBasicParam();

			String parentIdParam = "";

			// 如果有父级 ID，添加父级约束条件
			if (StringUtils.isNotEmpty(parentId)) {
				parentIdParam = "'" + parentId + "' in parents and ";
			}

			// 转义路径中的单引号，防止查询语句注入
			folderPath = folderPath.replace("'", "\\'");

			googleDriveApiParam.setFields("files(id,shortcutDetails)");
			googleDriveApiParam.setQ(parentIdParam + "name = '" + folderPath + "' and trashed = false");

			return googleDriveApiParam.toString();
		}


		/**
		 * 构建文件列表查询的 API 请求参数。
		 * <p>
		 * 查询指定文件夹下的所有文件和子目录（排除已删除的），
		 * 返回文件的基本信息字段。
		 * </p>
		 *
		 * @param folderId  Google Drive 文件夹 ID
		 * @param pageToken 分页 token
		 * @return URL 查询参数字符串
		 */
		public String getFileListParam(String folderId, String pageToken) {
			GoogleDriveAPIParam googleDriveAPIParam = getBasicParam();

			// 请求文件的基本信息字段
			googleDriveAPIParam.setFields("files(id,name,mimeType,shortcutDetails,size,modifiedTime),nextPageToken");
			googleDriveAPIParam.setQ("'" + folderId + "' in parents and trashed = false");
			googleDriveAPIParam.setPageToken(pageToken);
			googleDriveAPIParam.setPageSize(DEFAULT_PAGE_SIZE);
			return googleDriveAPIParam.toString();
		}


		/**
		 * 构建根据关键字和路径搜索文件的 API 请求参数。
		 * <p>
		 * 在指定文件夹下按文件名进行模糊搜索（contains 匹配），
		 * 排除已删除的文件。
		 * </p>
		 *
		 * @param folderId  搜索的父文件夹 ID，为空时搜索全部
		 * @param pageToken 分页 token
		 * @param keyword   搜索关键字
		 * @return URL 查询参数字符串
		 */
		public String getSearchParam(String folderId, String pageToken, String keyword) {
			GoogleDriveAPIParam googleDriveAPIParam = getBasicParam();

			String parentIdParam = "";
			if (StringUtils.isNotEmpty(folderId)) {
				parentIdParam = "'" + folderId + "' in parents and ";
			}

			googleDriveAPIParam.setFields("files(id,name,mimeType,shortcutDetails,size,modifiedTime),nextPageToken");
			googleDriveAPIParam.setQ(parentIdParam + " name contains '" + keyword + "' and trashed = false");
			googleDriveAPIParam.setPageToken(pageToken);
			googleDriveAPIParam.setPageSize(DEFAULT_PAGE_SIZE);
			return googleDriveAPIParam.toString();
		}


		/**
		 * 获取基础参数配置。
		 * <p>
		 * 判断当前存储源是否为团队盘：
		 * - 如果配置了 driveId，则为团队盘，corpora 设为 "drive"
		 * - 否则为个人云盘，corpora 设为 "user"
		 * </p>
		 *
		 * @return 基础参数对象
		 */
		public GoogleDriveAPIParam getBasicParam() {
			GoogleDriveAPIParam googleDriveAPIParam = new GoogleDriveAPIParam();
			String driveId = param.getDriveId();

			// 判断是否是团队盘，如果是，则需要添加团队盘的参数
			boolean isTeamDrive = StringUtils.isNotEmpty(driveId);

			googleDriveAPIParam.setCorpora("user");
			googleDriveAPIParam.setIncludeItemsFromAllDrives(true);
			googleDriveAPIParam.setSupportsAllDrives(true);
			if (isTeamDrive) {
				googleDriveAPIParam.setDriveId(driveId);
				googleDriveAPIParam.setCorpora("drive");
			}

			return googleDriveAPIParam;
		}

		/**
		 * 将请求对象转换为 URL 参数字符串（默认对值进行 URL 编码）。
		 *
		 * @return URL 查询参数字符串
		 */
		@Override
		public String toString() {
			return toString(true);
		}

		/**
		 * 将请求对象转换为 URL 参数字符串。
		 * <p>
		 * 通过反射遍历所有字段，将非空且非默认值的字段拼接为 URL 查询参数。
		 * 跳过以 "this" 开头的字段和值为 false 的 boolean 字段。
		 * </p>
		 *
		 * @param encodeValue 是否对参数值进行 URL 编码
		 * @return URL 查询参数字符串
		 */
		public String toString(boolean encodeValue) {
			// 通过反射获取所有字段
			Field[] fields = ReflectUtil.getFields(this.getClass());

			UrlQuery urlQuery = new UrlQuery();

			for (Field field : fields) {
				// 跳过以 "this" 开头的内部字段
				if (StringUtils.startWith(field.getName(), "this")) {
					continue;
				}
				Object fieldValue = ReflectUtil.getFieldValue(this, field);

				// 只添加非空且非默认值的字段
				if (ObjectUtil.isNotEmpty(fieldValue) && ObjectUtil.notEqual(fieldValue, false)) {
					urlQuery.add(field.getName(),
							encodeValue ?
									URLEncoder.encode(fieldValue.toString(), StandardCharsets.UTF_8)
									:
									fieldValue.toString()
					);
				}
			}
			return urlQuery.toString();
		}
	}


	/**
	 * 检查 HTTP 响应是否为错误状态码（4xx 或 5xx），如果是则抛出系统异常。
	 * <p>
	 * 适用于 Hutool 的 HttpResponse 对象。
	 * </p>
	 *
	 * @param httpResponse HTTP 响应对象
	 * @throws SystemException 当响应状态码表示错误时抛出
	 */
	private void checkHttpResponseIsError(HttpResponse httpResponse) {
		if (HttpStatus.valueOf(httpResponse.getStatus()).isError()) {
			int statusCode = httpResponse.getStatus();
			String responseBody = httpResponse.body();
			String msg = String.format("statusCode: %s, responseBody: %s", statusCode, responseBody);
			throw new SystemException(msg);
		}
	}

	/**
	 * 检查 HTTP 响应是否为错误状态码（4xx 或 5xx），如果是则抛出系统异常。
	 * <p>
	 * 适用于 Apache HttpClient 的 CloseableHttpResponse 对象。
	 * </p>
	 *
	 * @param closeableHttpResponse HTTP 响应对象
	 * @throws IOException       读取响应体时可能抛出的 IO 异常
	 * @throws SystemException   当响应状态码表示错误时抛出
	 */
	private void checkHttpResponseIsError(CloseableHttpResponse closeableHttpResponse) throws IOException {
		int statusCode = closeableHttpResponse.getStatusLine().getStatusCode();
		if (HttpStatus.valueOf(statusCode).isError()) {
			HttpEntity responseEntity = closeableHttpResponse.getEntity();
			String responseBody = EntityUtils.toString(responseEntity, StandardCharsets.UTF_8);
			String msg = String.format("statusCode: %s, responseBody: %s", statusCode, responseBody);
			throw new SystemException(msg);
		}
	}

	/**
	 * 获取存储源的元数据信息。
	 * <p>
	 * Google Drive 存储源使用代理上传方式。
	 * </p>
	 *
	 * @return 存储源元数据，上传类型为 PROXY
	 */
	@Override
	public StorageSourceMetadata getStorageSourceMetadata() {
		StorageSourceMetadata storageSourceMetadata = new StorageSourceMetadata();
		storageSourceMetadata.setUploadType(StorageSourceMetadata.UploadType.PROXY);
		return storageSourceMetadata;
	}

	/**
	 * 为 HTTP 请求添加通用的认证头和配置。
	 * <p>
	 * 检查并获取有效的 AccessToken，然后设置为 Bearer 认证头。
	 * </p>
	 *
	 * @param httpRequest 原始 HTTP 请求
	 * @return 添加了认证头的 HTTP 请求
	 */
	private HttpRequest commonHttpRequest(HttpRequest httpRequest) {
		String accessToken = checkExpiredAndGetAccessToken();
		httpRequest.bearerAuth(accessToken);
		return httpRequest;
	}

	/**
	 * 检查 AccessToken 是否过期，并获取有效的 AccessToken。
	 * <p>
	 * 该方法会在每次 API 调用前被调用，用于确保使用有效的 AccessToken。
	 * 如果 Token 不存在或已过期，使用双重检查锁定（DCL）机制确保线程安全地刷新 Token。
	 * </p>
	 *
	 * @return 当前有效的 AccessToken
	 * @throws SystemException 当 Token 刷新失败且无可用 Token 时抛出
	 */
	private String checkExpiredAndGetAccessToken() {
		// 从缓存中获取刷新令牌信息
		RefreshTokenCacheBO.RefreshTokenInfo refreshTokenInfo = RefreshTokenCacheBO.getRefreshTokenInfo(storageId);

		// 如果 Token 不存在或已过期，需要刷新
		if (refreshTokenInfo == null || refreshTokenInfo.isExpired()) {
			// 使用双重检查锁定机制，确保同一个 storageId 只会有一个线程在刷新 AccessToken
			synchronized (("storage-refresh-" + storageId).intern()) {
				// 双重检查，再次从缓存中获取，确认是否其他线程已经刷新过
				refreshTokenInfo = RefreshTokenCacheBO.getRefreshTokenInfo(storageId);
				if (refreshTokenInfo == null || refreshTokenInfo.isExpired()) {
					log.info("{} AccessToken 未获取或已过期, 尝试刷新.", getStorageSimpleInfo());
					refreshAccessToken();
					refreshTokenInfo = RefreshTokenCacheBO.getRefreshTokenInfo(storageId);
				}
			}
		}

		// 刷新后仍为空则抛出异常
		if (refreshTokenInfo == null) {
			throw new SystemException("存储源 " + storageId + " AccessToken 刷新失败: 未找到刷新令牌信息.");
		}

		return refreshTokenInfo.getData().getAccessToken();
	}

}