package im.tlgxy.cloud.core.exception.biz;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import lombok.Getter;

/**
 * 无效的存储源异常
 * <p>
 * 当系统尝试访问一个不存在或初始化失败的存储源时抛出此异常。
 * 通过存储源 ID（{@code storageId}）或存储源别名（{@code storageKey}）来标识出错的存储源，
 * 两种标识方式互斥，一次只使用其中一种。
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class InvalidStorageSourceBizException extends BizException {

    /**
     * 无效存储源的数据库主键 ID
     */
	private final Integer storageId;

    /**
     * 无效存储源的唯一别名标识
     */
	private final String storageKey;

    /**
     * 通过存储源别名构造异常
     *
     * @param storageKey 存储源别名
     */
	public InvalidStorageSourceBizException(String storageKey) {
		super(ErrorCode.INVALID_STORAGE_SOURCE);
		this.storageKey = storageKey;
		this.storageId = null;
	}

    /**
     * 通过存储源 ID 构造异常
     *
     * @param storageId 存储源 ID
     */
	public InvalidStorageSourceBizException(Integer storageId) {
		super(ErrorCode.INVALID_STORAGE_SOURCE);
		this.storageId = storageId;
		this.storageKey = null;
    }

}
