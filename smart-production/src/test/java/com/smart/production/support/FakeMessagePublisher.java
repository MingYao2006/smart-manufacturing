package com.smart.production.support;

import com.smart.production.service.MessagePublisher;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存版消息发布器（测试用 Fake）。
 *
 * <p>记录所有 enqueue 调用，用于断言"报工成功必须写 outbox"这类关键行为。
 * 不涉及任何数据库/MQ，测试快且稳定。
 */
public class FakeMessagePublisher implements MessagePublisher {

    /** 一条被记录的消息 */
    public record Published(String bizKey, String exchangeName, String routingKey, Object payload) {
    }

    private final List<Published> published = new ArrayList<>();

    @Override
    public void enqueue(String bizKey, String exchangeName, String routingKey, Object payload) {
        published.add(new Published(bizKey, exchangeName, routingKey, payload));
    }

    public List<Published> all() {
        return published;
    }

    public int count() {
        return published.size();
    }

    public boolean isEmpty() {
        return published.isEmpty();
    }

    public Published first() {
        return published.isEmpty() ? null : published.get(0);
    }
}
