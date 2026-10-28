package io.studentcloud.content.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.content.learning.LearningLibraryService;
import io.studentcloud.content.learning.LearningLibraryService.*;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning")
public class LearningController {
    private final LearningLibraryService library;
    public LearningController(LearningLibraryService library) { this.library = library; }
    public record CourseInput(String name, String semester) { }
    public record MaterialInput(long courseId, String storageKey, String path, String tags) { }
    public record RelinkInput(String storageKey, String path) { }

    @GetMapping("/courses") public ApiResponse<List<Course>> courses(HttpServletRequest request) {
        return ApiResponse.success(library.listCourses(AuthenticationFilter.actor(request)));
    }
    @PostMapping("/courses") public ApiResponse<Course> create(@RequestBody CourseInput input, HttpServletRequest request) {
        return ApiResponse.success(library.createCourse(AuthenticationFilter.actor(request), input.name(), input.semester()));
    }
    @PutMapping("/courses/{id}") public ApiResponse<Course> update(@PathVariable long id, @RequestBody CourseInput input, HttpServletRequest request) {
        return ApiResponse.success(library.updateCourse(AuthenticationFilter.actor(request), id, input.name(), input.semester()));
    }
    @DeleteMapping("/courses/{id}") public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest request) {
        library.deleteCourse(AuthenticationFilter.actor(request), id); return ApiResponse.success(null);
    }
    @GetMapping("/materials") public ApiResponse<List<Material>> materials(@RequestParam long courseId, HttpServletRequest request) {
        return ApiResponse.success(library.listMaterials(AuthenticationFilter.actor(request), courseId));
    }
    @PostMapping("/materials") public ApiResponse<Material> add(@RequestBody MaterialInput input, HttpServletRequest request) {
        return ApiResponse.success(library.addMaterial(AuthenticationFilter.actor(request), input.courseId(), input.storageKey(), input.path(), input.tags()));
    }
    @PostMapping("/materials/{id}/refresh") public ApiResponse<Material> refresh(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success(library.refresh(AuthenticationFilter.actor(request), id));
    }
    @PostMapping("/materials/{id}/relink") public ApiResponse<Material> relink(@PathVariable long id, @RequestBody RelinkInput input, HttpServletRequest request) {
        return ApiResponse.success(library.relink(AuthenticationFilter.actor(request), id, input.storageKey(), input.path()));
    }
    @DeleteMapping("/materials/{id}") public ApiResponse<Void> remove(@PathVariable long id, HttpServletRequest request) {
        library.deleteMaterial(AuthenticationFilter.actor(request), id); return ApiResponse.success(null);
    }
    @GetMapping("/search") public ApiResponse<SearchResult> search(@RequestParam(required=false) Long courseId, @RequestParam String query,
            @RequestParam(defaultValue="8") int limit, HttpServletRequest request) {
        return ApiResponse.success(library.search(AuthenticationFilter.actor(request), courseId, query, limit));
    }
}
