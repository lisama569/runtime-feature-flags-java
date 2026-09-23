package com.github.lisama569.runtimefeatureflagsjava.web;

import com.github.lisama569.runtimefeatureflagsjava.manager.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.model.Decision;
import com.github.lisama569.runtimefeatureflagsjava.model.FlagSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.model.RefreshResult;
import com.github.lisama569.runtimefeatureflagsjava.model.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示用 REST 入口。
 * <ul>
 *   <li>GET  /flags/{key}?visitorId=...&region=...&tenant=...  判定（含快照标识、原因与 trace）</li>
 *   <li>POST /admin/flags/refresh                             热更新（失败保持旧配置，返回可区分原因）</li>
 *   <li>POST /admin/flags/kill?on=true|false                  紧急关闭 / 解除</li>
 *   <li>POST /admin/flags/rollback/{version}                  回滚到历史版本</li>
 *   <li>GET  /admin/flags/snapshots                           查看当前与历史快照</li>
 * </ul>
 */
@RestController
public class FeatureFlagController {

    private final FeatureFlagManager manager;

    public FeatureFlagController(FeatureFlagManager manager) {
        this.manager = manager;
    }

    @GetMapping("/flags/{key}")
    public Decision evaluate(@PathVariable String key,
                             @RequestParam(required = false) String visitorId,
                             @RequestParam(required = false) String region,
                             @RequestParam(required = false) String tenant) {
        RequestContext context = new RequestContext(visitorId);
        if (region != null) {
            context.with("region", region);
        }
        if (tenant != null) {
            context.with("tenant", tenant);
        }
        return manager.evaluate(key, context);
    }

    @PostMapping("/admin/flags/refresh")
    public RefreshResult refresh() {
        return manager.refresh();
    }

    @PostMapping("/admin/flags/kill")
    public Map<String, Object> emergencyKill(@RequestParam(defaultValue = "true") boolean on) {
        manager.emergencyKill(on);
        FlagSnapshot snapshot = manager.currentSnapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("engaged", snapshot != null && snapshot.isGlobalKillSwitch());
        body.put("activeSnapshot", snapshot == null ? null : snapshot.getSnapshotId());
        return body;
    }

    @PostMapping("/admin/flags/rollback/{version}")
    public Map<String, Object> rollback(@PathVariable long version) {
        boolean ok = manager.rollback(version);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rolledBack", ok);
        FlagSnapshot snapshot = manager.currentSnapshot();
        body.put("activeSnapshot", snapshot == null ? null : snapshot.getSnapshotId());
        if (!ok) {
            body.put("availableVersions", manager.history().stream().map(FlagSnapshot::getVersion).toList());
        }
        return body;
    }

    @GetMapping("/admin/flags/snapshots")
    public List<Map<String, Object>> snapshots() {
        return manager.history().stream()
                .map(snapshot -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("version", snapshot.getVersion());
                    view.put("snapshotId", snapshot.getSnapshotId());
                    view.put("globalKillSwitch", snapshot.isGlobalKillSwitch());
                    view.put("flags", snapshot.getFlags().keySet());
                    return view;
                })
                .toList();
    }
}
