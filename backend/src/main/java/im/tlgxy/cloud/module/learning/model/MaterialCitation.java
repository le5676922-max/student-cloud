package im.tlgxy.cloud.module.learning.model;

/** Server-generated evidence; segment numbers refer to extracted text, not PDF pages. */
public record MaterialCitation(String reference, Integer materialId, Integer courseId,
                               String name, String storageKey, String path,
                               int segment, String excerpt, int score) { }
