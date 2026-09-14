package com.mall.admin.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 日志捕获（用于断言"每请求**恰好一条** {@code log.warn}"）。
 *
 * <h2>为什么要自己写一个 Appender</h2>
 * Logback 自带的 {@code ch.qos.logback.core.read.ListAppender} 内部是一个普通 {@code ArrayList}
 * —— 而看板的取数是**并发**的（三个域并行），断言又恰好是"条数"。用非线程安全的收集器
 * 去断言一条关于并发的性质，等于把结论建在竞态上。这里用 {@link CopyOnWriteArrayList}。
 *
 * <p>挂在 {@code com.mall.admin} 这个 logger 上（而不是某个具体类上）：
 * 判据是"**这一次请求期间，整个服务只打了一条 WARN**"——只盯一个类的话，
 * 别处（缓存降级、请求体解析等）多打一条就看不见了。
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final AppenderBase<ILoggingEvent> appender;
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    private LogCapture(String loggerName) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerName);
        this.appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                events.add(event);
            }
        };
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        // 确保 WARN 不会被级别过滤掉（dev profile 已把 com.mall.admin 设为 debug）
        logger.setLevel(Level.DEBUG);
    }

    public static LogCapture on(String loggerName) {
        return new LogCapture(loggerName);
    }

    /** 清空已收集的事件（在"发请求之前"调用，让计数只覆盖一次请求） */
    public void clear() {
        events.clear();
    }

    public List<ILoggingEvent> warns() {
        List<ILoggingEvent> out = new ArrayList<>();
        for (ILoggingEvent event : events) {
            if (event.getLevel() == Level.WARN) {
                out.add(event);
            }
        }
        return out;
    }

    public List<String> warnMessages() {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent event : warns()) {
            out.add(event.getFormattedMessage());
        }
        return out;
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
