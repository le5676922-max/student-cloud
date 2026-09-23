package im.tlgxy.cloud.module.ai.model.knowledge;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 知识库中的一条项目文档。 */
@Data
public class KnowledgeDocument {

    private String id;

    private String title;

    private List<String> tags = new ArrayList<>();

    private String source;

    private String audience;

    private String content;
}
