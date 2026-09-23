package com.github.lisama569.runtimefeatureflagsjava.flag.config;

import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.ConfigSource;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 开关模块的 Spring 装配。
 * <p>
 * 演示用两级来源（数值越大优先级越高）：
 * <ul>
 *   <li>{@code local-defaults}（priority=10）：本地基线配置</li>
 *   <li>{@code remote-center}（priority=100）：模拟远端配置中心，同键覆盖本地</li>
 * </ul>
 * Bean 以 List 形式暴露来源，测试可直接替换/改写；启动时执行首次加载，
 * 若首次加载失败（来源缺失/非法取值），系统保持"无快照"状态，
 * 此时所有判定 fail-CLOSED，语义明确且可解释。
 */
@Configuration
public class FlagSpringConfig {

    private static final Logger log = LoggerFactory.getLogger(FlagSpringConfig.class);

    @Bean("localDefaultsSource")
    public InMemoryConfigSource localDefaultsSource() {
        InMemoryConfigSource source = new InMemoryConfigSource("local-defaults", 10);
        source.setProperties(Map.of(
                "feature.checkout-redesign.enabled", "false",
                "feature.checkout-redesign.percentage", "0",
                "feature.new-search.enabled", "true",
                "feature.new-search.percentage", "50"
        ));
        return source;
    }

    @Bean("remoteCenterSource")
    public InMemoryConfigSource remoteCenterSource() {
        InMemoryConfigSource source = new InMemoryConfigSource("remote-center", 100);
        source.setProperties(Map.of(
                // 远端覆盖本地：新结算页放量 30% 并定向 beta 渠道、含白名单
                "feature.checkout-redesign.enabled", "true",
                "feature.checkout-redesign.percentage", "30",
                "feature.checkout-redesign.allowlist", "vip-001,vip-002",
                "feature.checkout-redesign.target.channel", "beta"
        ));
        return source;
    }

    @Bean
    public FeatureFlagManager featureFlagManager(List<ConfigSource> sources) {
        FeatureFlagManager manager = new FeatureFlagManager(sources);
        RefreshOutcome outcome = manager.refresh();
        if (outcome.accepted()) {
            log.info("[FLAG-STARTUP] 初始配置加载成功 snapshot={}", outcome.activeSnapshotId());
        } else {
            log.error("[FLAG-STARTUP] 初始配置加载被拒绝 reason={} detail={} —— "
                    + "在一次成功刷新之前，所有开关判定 fail-CLOSED",
                    outcome.failureReason(), outcome.detail());
        }
        return manager;
    }
}
