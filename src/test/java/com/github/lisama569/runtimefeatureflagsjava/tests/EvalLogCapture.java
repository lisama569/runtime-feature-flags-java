package com.github.lisama569.runtimefeatureflagsjava.tests;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.github.lisama569.runtimefeatureflagsjava.flag.eval.FlagEvaluator;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 捕获 [FLAG-EVAL] 判定日志的测试工具：把线程安全的 appender 挂到
 * 判定器与开关门面两个 logger 上，用例结束后可逐行断言对账信息
 * （配置快照标识 / 命中的停用记录 / 各字段出处来源）。
 * 并发用例中多条判定日志会全部进入队列，不会发生丢失或半行。
 */
final class EvalLogCapture implements AutoCloseable {

    private final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
    private final List<Logger> attached;
    private final AppenderBase<ILoggingEvent> appender;

    private EvalLogCapture() {
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                lines.add(event.getFormattedMessage());
            }
        };
        appender.setName("eval-log-capture");
        attached = List.of(
                (Logger) LoggerFactory.getLogger(FlagEvaluator.class),
                (Logger) LoggerFactory.getLogger(FeatureFlagManager.class));
        for (Logger logger : attached) {
            appender.setContext(logger.getLoggerContext());
        }
        appender.start();
        for (Logger logger : attached) {
            logger.addAppender(appender);
        }
    }

    static EvalLogCapture start() {
        return new EvalLogCapture();
    }

    /** 全部 [FLAG-EVAL] 判定日志行（按产生顺序）。 */
    List<String> evalLines() {
        return lines.stream().filter(l -> l.contains("[FLAG-EVAL]")).toList();
    }

    /** 最近一次针对某开关的判定日志行。 */
    String lastEvalLineFor(String flagKey) {
        String found = null;
        for (String line : evalLines()) {
            if (line.contains("flag=" + flagKey + " ")) {
                found = line;
            }
        }
        return found;
    }

    @Override
    public void close() {
        for (Logger logger : attached) {
            logger.detachAppender(appender);
        }
        appender.stop();
    }
}
