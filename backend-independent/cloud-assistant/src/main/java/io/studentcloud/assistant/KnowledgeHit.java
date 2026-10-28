package io.studentcloud.assistant;

/** Published knowledge only, without the original private question or submitting account. */
public record KnowledgeHit(long id, String title, String answer, long version, KnowledgeScope scope) { }
