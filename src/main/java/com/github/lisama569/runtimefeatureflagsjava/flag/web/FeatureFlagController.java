package com.github.lisama569.runtimefeatureflagsjava.flag.web;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshOutcome;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.TreeMap;

/**
 * 开关查询与运维接口。
 * <ul>
 *   <li>GET  /api/flags/{key}/evaluate?visitorId=..&amp;attr.region=.. 判定查询</li>
 *   <li>POST /api/admin/refresh       热刷新（失败保旧）</li>
 *   <li>POST /api/admin/kill          紧急关闭（总闸）</li>
 *   <li>POST /api/admin/rollback      回滚到上一份快照</li>
 * </ul>
 */
@RestController
public class FeatureFlagController {

    private final FeatureFlagManager manager;

    public FeatureFlagController(FeatureFlagManager manager) {
        this.manager = manager;
    }

    @GetMapping("/api/flags/{key}/evaluate")
    public EvaluationResult evaluate(@PathVariable String key,
                                     @RequestParam String visitorId,
                                     @RequestParam Map<String, String> allParams) {
        Map<String, String> attrs = new TreeMap<>();
        for (Map.Entry<String, String> e : allParams.entrySet()) {
            if (e.getKey().startsWith("attr.")) {
                attrs.put(e.getKey().substring("attr.".length()), e.getValue());
            }
        }
        return manager.evaluate(key, new RequestContext(visitorId, attrs));
    }

    @PostMapping("/api/admin/refresh")
    public RefreshOutcome refresh() {
        return manager.refresh();
    }

    @PostMapping("/api/admin/kill")
    public RefreshOutcome kill(@RequestParam(defaultValue = "emergency") String reason) {
        return manager.emergencyShutdown(reason);
    }

    @PostMapping("/api/admin/rollback")
    public RefreshOutcome rollback() {
        return manager.rollback();
    }
}
