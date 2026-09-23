package im.tlgxy.cloud.module.storage.aspect;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.CollectionUtils;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.password.model.dto.VerifyResultDTO;
import im.tlgxy.cloud.module.password.service.PasswordConfigService;
import im.tlgxy.cloud.module.storage.annotation.CheckPassword;
import im.tlgxy.cloud.module.storage.annotation.CheckPasswords;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹密码校验切面。
 *
 * 该切面负责拦截所有标注了 {@link CheckPassword} 或 {@link CheckPasswords} 注解的 Controller 方法，
 * 在方法执行前提取请求中的存储源 key、目标路径和密码参数，通过 {@link PasswordConfigService} 进行密码校验。
 *
 * 核心工作流程：
 * 1. 通过 AOP 环绕通知拦截目标方法；
 * 2. 从方法注解中获取 SpEL 表达式，用于提取参数值；
 * 3. 根据存储源 key 查找对应的存储源 ID 和文件服务实例；
 * 4. 拼接完整路径后调用密码服务进行校验；
 * 5. 校验通过则放行请求，失败则抛出业务异常。
 *
 * @author zhaojun
 */
@Aspect
@Component
@Slf4j
public class CheckPasswordAspect {

	/**
	 * 密码配置服务，用于校验用户输入的密码是否正确。
	 */
	@Resource
	private PasswordConfigService passwordConfigService;

	/**
	 * 存储源服务，用于根据存储源 key 查找对应的存储源 ID。
	 */
	@Resource
	private StorageSourceService storageSourceService;

	/**
	 * 环绕通知：对标注了 {@link CheckPassword} 或 {@link CheckPasswords} 注解的方法进行密码校验。
	 *
	 * 此方法会收集方法上所有 {@link CheckPassword} 注解实例（通过容器注解或直接注解），
	 * 依次对每个注解配置的规则进行密码校验，只有所有规则都通过校验后才放行请求。
	 *
	 * @param   point
	 *          AOP 连接点，包含目标方法的所有信息（方法签名、参数等）
	 *
	 * @return  目标方法执行后的返回值
	 *
	 * @throws  Throwable
	 *          当密码校验失败时抛出 {@link BizException}，或目标方法自身抛出的异常
	 */
	@Around(value = "@annotation(im.tlgxy.cloud.module.storage.annotation.CheckPassword) || @annotation(im.tlgxy.cloud.module.storage.annotation.CheckPasswords)")
	public Object around(ProceedingJoinPoint point) throws Throwable {
		Signature s = point.getSignature();
		MethodSignature ms = (MethodSignature) s;
		Method method = ms.getMethod();
		List<CheckPassword> checkPasswordList = new ArrayList<>();

		// 收集所有 CheckPassword 注解实例：优先从容器注解获取，其次从单个注解获取
		CheckPasswords checkPasswords = method.getAnnotation(CheckPasswords.class);
		CheckPassword checkPassword = method.getAnnotation(CheckPassword.class);
		if (checkPasswords != null) {
			CollectionUtils.addAll(checkPasswordList, checkPasswords.value());
		} else if (checkPassword != null) {
			checkPasswordList.add(checkPassword);
		} else {
			// 如果没有找到任何注解，直接放行
			return point.proceed();
		}

		// 遍历每个校验规则，逐一进行密码校验
		for (CheckPassword item : checkPasswordList) {
			boolean pathIsDirectory = item.pathIsDirectory();
			String storageKeyFieldExpression = item.storageKeyFieldExpression();
			String passwordFieldExpression = item.passwordFieldExpression();
			String pathFieldExpression = item.pathFieldExpression();

			Object[] args = point.getArgs();

			// 使用 SpEL 表达式从方法参数中提取对应的值
			String storageKeyFieldValue = getFieldValue(args, storageKeyFieldExpression);
			String passwordFieldValue = getFieldValue(args, passwordFieldExpression);
			String pathFieldValue = getFieldValue(args, pathFieldExpression);

			// 如果路径不是文件夹，则取其父级目录路径
			if (!pathIsDirectory) {
				pathFieldValue = FileUtils.getParentPath(pathFieldValue);
			}

			// 根据存储源 key 查找对应的存储源 ID
			Integer storageId = storageSourceService.findIdByKey(storageKeyFieldValue);

			if (storageId == null) {
				// 存储源不存在，抛出业务异常
				throw new BizException(ErrorCode.BIZ_STORAGE_NOT_FOUND);
			}

			// 获取存储源对应的文件服务实例，并拼接用户基础路径得到完整路径
			AbstractBaseFileService<?> targetService = StorageSourceContext.getByStorageId(storageId);
			String fullPath = StringUtils.concat(targetService.getCurrentUserBasePath(), pathFieldValue);

			// 调用密码服务进行校验
			VerifyResultDTO verifyResultDTO = passwordConfigService.verifyPassword(storageId, fullPath, passwordFieldValue);
			if (!verifyResultDTO.isPassed()) {
				// 密码校验失败，抛出业务异常并携带错误码
				throw new BizException(verifyResultDTO.getErrorCode());
			}
		}
		// 所有规则校验通过，执行目标方法
		return point.proceed();
	}

	/**
	 * 使用 SpEL（Spring Expression Language）表达式从目标对象中提取字段值。
	 *
	 * 例如表达式 "#p0.storageKey" 表示获取第一个参数的 storageKey 字段值。
	 *
	 * @param   target
	 *          目标对象（通常为方法参数数组）
	 *
	 * @param   expression
	 *          SpEL 表达式字符串
	 *
	 * @return  提取到的字符串值
	 */
	public String getFieldValue(Object target, String expression) {
		SpelExpressionParser parser = new SpelExpressionParser();
		Expression exp = parser.parseExpression(expression);
		return (String) exp.getValue(target);
	}

}
