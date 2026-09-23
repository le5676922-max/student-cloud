package im.tlgxy.cloud.core.controller;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ZipUtil;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.FileResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.Date;

/**
 * 系统日志管理控制器
 * <p>
 * 提供系统诊断日志的下载功能。当用户需要排查系统问题时，可以通过此接口
 * 下载包含系统运行日志的压缩包，便于离线分析和问题定位。
 * </p>
 *
 * @author zhaojun
 */
@Tag(name = "日志")
@ApiSort(8)
@Slf4j
@RestController
@RequestMapping("/admin")
public class LogController {

    /**
     * TlgxyCloud 系统日志文件的存放路径，从配置文件中的 {@code tongligong-student-cloud.log.path} 读取
     */
    @Value("${tongligong-student-cloud.log.path}")
    private String studentCloudLogPath;

    /**
     * 下载系统诊断日志
     * <p>
     * 将指定路径下的所有日志文件打包成 ZIP 压缩文件供用户下载。
     * 文件名格式为 "TlgxyCloud 诊断日志 - yyyy-MM-dd HH:mm:ss.zip"，
     * 包含时间戳以便区分不同时间点的日志。
     * 该方法在演示站点中被 {@link DemoDisable} 注解禁用。
     * </p>
     *
     * @return 包含日志 ZIP 文件的 ResponseEntity，供浏览器下载
     */
    @GetMapping("/log/download")
    @Operation(summary = "下载系统日志")
    @DemoDisable
    public ResponseEntity<Resource> downloadLog() {
        if (log.isDebugEnabled()) {
            log.debug("下载诊断日志");
        }

        // 将日志目录下的所有文件打包为 ZIP 文件
        File fileZip = ZipUtil.zip(studentCloudLogPath);
        // 生成带时间戳的文件名，格式：TlgxyCloud 诊断日志 - 2024-01-01 12:00:00.zip
        String currentDate = DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss");
        // 通过文件响应工具返回下载响应
        return FileResponseUtil.exportSingleThread(fileZip, "TlgxyCloud 诊断日志 - " + currentDate + ".zip");
    }

}
