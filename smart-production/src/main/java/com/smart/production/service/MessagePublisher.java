package com.smart.production.service;

/**
 * 消息发布器抽象（依赖倒置）。
 *
 * <h3>为什么抽成接口</h3>
 * 计划书第 7 周明确指出："测试写不动 → 业务代码耦合严重，难 mock →
 * <b>把外部依赖（Feign/MQ）抽成接口，测试时 mock</b>"。
 *
 * <p>本接口把"写本地消息表"这个外部 I/O 依赖抽象出来：
 * <ul>
 *   <li>生产实现：{@link OutboxSenderService}（写 outbox 表 + publisher confirm 投递）</li>
 *   <li>测试实现：内存 Fake，验证"报工成功必须写 outbox"这类关键行为</li>
 * </ul>
 */
public interface MessagePublisher {

    /**
     * 在<b>当前事务内</b>登记一条待投递消息。
     *
     * @param bizKey       业务幂等键，如 report:123:order-progress
     * @param exchangeName 目标交换机
     * @param routingKey   路由键
     * @param payload      消息体（会被序列化为 JSON）
     */
    void enqueue(String bizKey, String exchangeName, String routingKey, Object payload);
}
