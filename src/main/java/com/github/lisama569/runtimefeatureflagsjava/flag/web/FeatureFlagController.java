package com.github.lisama569.runtimefeatureflagsjava.flag.web;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableDirective;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshOutcome;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 开关查询与运维接口。
 * <ul>
 *   <li>GET  /api/flags/{key}/evaluate?visitorId=..&amp;attr.region=.. 判定查询</li>
 *   <li>POST /api/admin/refresh       热刷新（失败保旧，停用不受影响）</li>
 *   <li>POST /api/admin/kill          紧急关闭（全局总闸）</li>
 *   <li>POST /api/admin/rollback      回滚到上一份快照（停用不回滚）</li>
 *   <li>POST /api/admin/disable       按开关紧急停用（keys 逗号分隔，可带 ttlMillis）</li>
 *   <li>POST /api/admin/restore       恢复某开关的全部停用</li>
 *   <li>POST /api/admin/restore-directive  按指令 id 恢复单条停用</li>
 *   <li>GET  /api/admin/disables      查询当前全部生效中的停用指令</li>
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

    /**
     * 按开关紧急停用。
     *
     * @param keys      一个或多个开关键，逗号分隔（原子批量：全部成功或全部拒绝）
     * @param reason    停用原因（必填）
     * @param operator  操作人（必填）
     * @param ttlMillis 可选，有效期毫秒；不传或 0 表示不限期
     */
    @PostMapping("/api/admin/disable")
    public DisableOutcome disable(@RequestParam String keys,
                                  @RequestParam String reason,
                                  @RequestParam String operator,
                                  @RequestParam(defaultValue = "0") long ttlMillis) {
        List<String> flagKeys = Arrays.stream(keys.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return manager.disable(flagKeys, reason, operator, ttlMillis);
    }

    /** 恢复某开关下全部生效中的停用。 */
    @PostMapping("/api/admin/restore")
    public DisableOutcome restore(@RequestParam String flagKey) {
        return manager.restore(flagKey);
    }

    /** 按指令 id 恢复单条停用。 */
    @PostMapping("/api/admin/restore-directive")
    public DisableOutcome restoreDirective(@RequestParam String id) {
        return manager.restoreById(id);
    }

    /** 查询当前全部生效中的停用指令（含原因/操作人/到期时间/所基于快照）。 */
    @GetMapping("/api/admin/disables")
    public List<DisableDirective> listDisables() {
        return manager.activeDisables();
    }
}
