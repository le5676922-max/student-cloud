package im.tlgxy.cloud.module.learning.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.learning.model.*;
import im.tlgxy.cloud.module.learning.service.LearningLibraryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/learning")
@SaCheckLogin
@RequiredArgsConstructor
public class LearningController {
    private final LearningLibraryService library;
    @GetMapping("/courses")
    public AjaxJson<List<LearningCourse>> courses() { return AjaxJson.getSuccessData(library.courses()); }
    @PostMapping("/courses")
    public AjaxJson<LearningCourse> create(@Valid @RequestBody LearningRequests.Course request) {
        return AjaxJson.getSuccessData(library.saveCourse(null, request));
    }
    @PutMapping("/courses/{id}")
    public AjaxJson<LearningCourse> update(@PathVariable int id, @Valid @RequestBody LearningRequests.Course request) {
        return AjaxJson.getSuccessData(library.saveCourse(id, request));
    }
    @DeleteMapping("/courses/{id}")
    public AjaxJson<Void> deleteCourse(@PathVariable int id) { library.deleteCourse(id); return AjaxJson.getSuccess(); }
    @GetMapping("/materials")
    public AjaxJson<List<LearningMaterial>> materials(@RequestParam int courseId) {
        return AjaxJson.getSuccessData(library.materials(courseId));
    }
    @PostMapping("/materials")
    @ApiLimit(timeout = 60, maxCount = 20)
    public AjaxJson<LearningMaterial> add(@Valid @RequestBody LearningRequests.Material request) {
        return AjaxJson.getSuccessData(library.add(request));
    }
    @PostMapping("/materials/{id}/refresh")
    @ApiLimit(timeout = 60, maxCount = 20)
    public AjaxJson<LearningMaterial> refresh(@PathVariable int id) { return AjaxJson.getSuccessData(library.refresh(id)); }
    @PostMapping("/materials/{id}/relink")
    @ApiLimit(timeout = 60, maxCount = 20)
    public AjaxJson<LearningMaterial> relink(@PathVariable int id, @Valid @RequestBody LearningRequests.Relink request) {
        return AjaxJson.getSuccessData(library.relink(id, request));
    }
    @DeleteMapping("/materials/{id}")
    public AjaxJson<Void> deleteMaterial(@PathVariable int id) { library.deleteMaterial(id); return AjaxJson.getSuccess(); }
    @GetMapping("/search")
    @ApiLimit(timeout = 60, maxCount = 20)
    public AjaxJson<LearningLibraryService.SearchResult> search(@RequestParam String query, @RequestParam(required = false) Integer courseId) {
        return AjaxJson.getSuccessData(library.search(query, courseId));
    }
}
