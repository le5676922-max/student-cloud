package im.tlgxy.cloud.module.ai.model.bo;

/** 用户本轮对话中上传并解析出的临时附件内容。 */
public record AiAttachmentContent(String fileName, String content) {
}