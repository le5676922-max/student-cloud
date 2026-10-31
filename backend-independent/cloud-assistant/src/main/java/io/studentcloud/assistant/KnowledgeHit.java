package io.studentcloud.assistant;

/** 表示当前可检索的已发布知识及其引用信息。
 *
 * Published knowledge only, without the original private question or submitting account.
 */
public record KnowledgeHit(long id, String title, String answer, long version, KnowledgeScope scope) { }
