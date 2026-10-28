package io.studentcloud.assistant;

import io.studentcloud.account.Actor;
import io.studentcloud.common.ApplicationFault;

/** Course membership is resolved by its owning module; the assistant never reads course tables. */
@FunctionalInterface
public interface CourseKnowledgeAccess {
    void requireRead(Actor actor, long courseId);

    static CourseKnowledgeAccess unavailable() {
        return (actor, courseId) -> {
            throw new ApplicationFault("ASSISTANT_COURSE_UNAVAILABLE", 503, "课程知识访问尚未接入");
        };
    }
}
