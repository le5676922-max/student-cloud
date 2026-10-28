package io.studentcloud.content.http;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.content.learning.LearningLibraryService;
import org.springframework.web.bind.annotation.*;

/** Protected by the service-runtime internal credential filter. Account IDs never grant broader visibility. */
@RestController
public class InternalLearningController {
    private final LearningLibraryService library;
    private final AccountDirectory accounts;
    public InternalLearningController(LearningLibraryService library, AccountDirectory accounts) { this.library = library; this.accounts = accounts; }
    public record SearchInput(long accountId, Long courseId, String query, int limit) { }
    @PostMapping("/internal/content/learning/search")
    public ApiResponse<LearningLibraryService.SearchResult> search(@RequestBody SearchInput input) {
        return ApiResponse.success(library.search(accounts.account(input.accountId()), input.courseId(), input.query(), input.limit()));
    }
}
