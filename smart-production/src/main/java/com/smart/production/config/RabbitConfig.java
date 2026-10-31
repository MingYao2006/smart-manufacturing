package com.smart.production.config;

import com.smart.production.mq.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置（第 5 周任务 5.5 / 5.6）。
 *
 * <h3>三端加固（面试必讲）</h3>
 * <ol>
 *   <li><b>生产端</b>：本地消息表 outbox + publisher confirm（见 OutboxSenderService）</li>
 *   <li><b>Broker 端</b>：队列/消息持久化 + 死信交换机</li>
 *   <li><b>消费端</b>：重试 + 耗尽进 DLQ，不吞异常</li>
 * </ol>
 *
 * <p>⚠️ 本项目允许 MQ 不可用：通过 {@code smart.mq.enabled} 开关控制。
 * 关闭时 outbox 记录仍会落库（业务不受影响），MQ 恢复后由定时任务补发。
 * 这正是"MQ 宕机时业务不阻塞"的设计目标。
 */
@Configuration
@ConditionalOnProperty(name = "smart.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitConfig {

    /* ---------------- 交换机 ---------------- */

    @Bean
    public DirectExchange productionExchange() {
        // durable=true：Broker 重启后交换机仍存在
        return new DirectExchange(MqConstants.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(MqConstants.DLX_EXCHANGE, true, false);
    }

    /* ---------------- 队列 ---------------- */

    /**
     * 报工完成队列：绑定死信交换机。
     *
     * <p>{@code x-dead-letter-exchange}：消费失败且重试耗尽的死信消息会被路由到死信队列，
     * 而不是被丢弃。这样"有消息进死信"是<b>可观测</b>的（能看到、能告警、能重投）。
     */
    @Bean
    public Queue reportCompletedQueue() {
        return QueueBuilder.durable(MqConstants.QUEUE_REPORT_COMPLETED)
                .withArgument("x-dead-letter-exchange", MqConstants.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_DLQ)
                .build();
    }

    @Bean
    public Queue orderCompletedQueue() {
        return QueueBuilder.durable(MqConstants.QUEUE_ORDER_COMPLETED)
                .withArgument("x-dead-letter-exchange", MqConstants.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_DLQ)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(MqConstants.QUEUE_DLQ).build();
    }

    /* ---------------- 绑定 ---------------- */

    @Bean
    public Binding reportCompletedBinding() {
        return BindingBuilder.bind(reportCompletedQueue())
                .to(productionExchange())
                .with(MqConstants.RK_REPORT_COMPLETED);
    }

    @Bean
    public Binding orderCompletedBinding() {
        return BindingBuilder.bind(orderCompletedQueue())
                .to(productionExchange())
                .with(MqConstants.RK_ORDER_COMPLETED);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(dlxExchange())
                .with(MqConstants.RK_DLQ);
    }

    /* ---------------- 消息转换 ---------------- */

    /**
     * JSON 序列化：比默认 JDK 序列化可读、跨语言、体积小。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter mc) {
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(mc);
        // 🔑 mandatory=true：路由不到队列时触发 returns 回调（而不是静默丢弃）
        template.setMandatory(true);
        return template;
    }

    /**
     * 手动 ACK 的监听容器。
     *
     * <p>重试 3 次（间隔指数增长），耗尽后 reject 且不重新入队 → 进死信队列。
     * {@code defaultRequeueRejected=false} 是关键：否则失败消息会无限重投，
     * 把消费者线程打满（"毒消息"问题）。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory manualAckContainerFactory(ConnectionFactory cf,
                                                                        MessageConverter mc) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(cf);
        factory.setMessageConverter(mc);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(20);

        org.springframework.retry.support.RetryTemplate retry = new org.springframework.retry.support.RetryTemplate();
        org.springframework.retry.backoff.ExponentialBackOffPolicy policy =
                new org.springframework.retry.backoff.ExponentialBackOffPolicy();
        policy.setInitialInterval(1000);
        policy.setMultiplier(2.0);
        policy.setMaxInterval(10000);
        retry.setBackOffPolicy(policy);
        retry.setRetryPolicy(new org.springframework.retry.policy.SimpleRetryPolicy(3));
        factory.setRetryTemplate(retry);
        return factory;
    }
}
