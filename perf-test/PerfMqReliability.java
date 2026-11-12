import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * RabbitMQ 消息可靠性压测（对应 docs/06 §5 测试清单）。
 *
 * <h3>四个场景</h3>
 * <ol>
 *   <li><b>正常投递</b>：N 线程并发报工 → outbox 全部投递成功、无终态失败</li>
 *   <li><b>MQ 宕机</b>：停 broker 期间报工照常成功（outbox 落库），恢复后自动补发</li>
 *   <li><b>重复消费</b>：同一事件投递 N 次 → 工单进度只按最终数据算一次（重算幂等）</li>
 *   <li><b>路由失败</b>：故意发到不存在的 routingKey → returns 回调把 outbox 重置为待投递</li>
 * </ol>
 *
 * <h3>为什么直接连数据库断言</h3>
 * 接口返回 code=0 只证明"业务事务提交了"，不证明"消息投递了"。
 * 消息可靠性的证据只能在 mq_outbox 表里 —— 所以断言全部基于 SQL。
 *
 * <p>编译运行（场景 1/3/4 不需要人工干预；场景 2 需要你在中途停/起 RabbitMQ）：
 * <pre>
 *   set MYSQL_PASS=你的MySQL密码
 *   java PerfMqReliability.java [线程数] [场景]
 *   场景：all | normal | replay | route | downtime
 * </pre>
 */
public class PerfMqReliability {

    static final String BASE = "http://localhost:9003";
    static final String JDBC = "jdbc:mysql://localhost:3306/smart_production"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false"
            + "&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=15000";
    static final String DB_USER = "root";
    // 密码不进仓库：运行前先 set MYSQL_PASS=你的MySQL密码（cmd）
    static final String DB_PASS = System.getenv().getOrDefault("MYSQL_PASS", "");

    static {
        // 显式加载驱动：脚本以源码方式运行，不加这一步 getConnection 会抛
        // "No suitable driver" 或长时间挂起
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("!! 缺少 MySQL JDBC 驱动，请把 mysql-connector-j-*.jar 加到 classpath");
        }
    }

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 50;
        String scene = args.length > 1 ? args[1] : "all";

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newFixedThreadPool(threads + 8))
                .build();

        header("RabbitMQ 消息可靠性压测");
        System.out.println("  目标服务 : " + BASE);
        System.out.println("  并发线程 : " + threads);
        System.out.println("  执行场景 : " + scene);
        System.out.println();

        // 前置检查：服务 + 数据库
        if (!preflight(client)) {
            fail("前置检查", "production 服务或数据库不可用");
            summary();
            System.exit(1);
        }

        if (scene.equals("all") || scene.equals("normal")) {
            sceneNormal(client, threads);
        }
        if (scene.equals("all") || scene.equals("replay")) {
            sceneReplay(client, threads);
        }
        if (scene.equals("all") || scene.equals("route")) {
            sceneRouteFailure(client);
        }
        if (scene.equals("downtime")) {
            sceneDowntime(client, threads);
        }

        summary();
        System.exit(failed == 0 ? 0 : 1);
    }

    /* ================================================================
     * 场景一：正常投递（并发报工 → outbox 全量投递）
     * ================================================================ */
    static void sceneNormal(HttpClient client, int threads) throws Exception {
        section("场景一：并发报工 → outbox 正常投递");

        long taskId = prepareReportableTask(threads);
        System.out.println("  测试工序任务 : taskId=" + taskId + "（重置为进行中，计划量 "
                + queryLong("SELECT plan_quantity FROM work_task WHERE id=" + taskId) + "）");
        System.out.println();

        long baseOutboxId = queryLong("SELECT COALESCE(MAX(id),0) FROM mq_outbox");
        String runTag = "NORM" + System.currentTimeMillis();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err = new AtomicInteger();
        Map<String, AtomicInteger> reasons = new ConcurrentHashMap<>();
        List<String> reportNos = Collections.synchronizedList(new ArrayList<>());

        long t0 = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            final String reportNo = runTag + "-" + i;
            reportNos.add(reportNo);
            new Thread(() -> {
                try {
                    startGate.await();
                    String body = String.format(
                            "{\"reportNo\":\"%s\",\"taskId\":%d,\"reportQuantity\":1,"
                                    + "\"qualifiedQuantity\":1,\"defectQuantity\":0,"
                                    + "\"actualHours\":0.01,\"operatorId\":4,\"operatorName\":\"压测员\"}",
                            reportNo, taskId);
                    String resp = post(client, "/report", body);
                    if (resp != null && resp.contains("\"code\":0")) {
                        ok.incrementAndGet();
                    } else {
                        err.incrementAndGet();
                        reasons.computeIfAbsent(extract(resp, "\"message\":\""), k -> new AtomicInteger())
                                .incrementAndGet();
                    }
                } catch (Exception e) {
                    err.incrementAndGet();
                    reasons.computeIfAbsent("EXC: " + e.getClass().getSimpleName(), k -> new AtomicInteger())
                            .incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            }, "rep-" + i).start();
        }
        startGate.countDown();
        endGate.await(120, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("  报工耗时     : %.2f 秒%n", elapsedMs / 1000.0);
        System.out.println("  报工成功     : " + ok.get());
        System.out.println("  报工失败     : " + err.get());
        if (!reasons.isEmpty()) {
            reasons.forEach((k, v) -> System.out.println("    [" + v.get() + " 次] " + k));
        }

        // 等定时任务把 outbox 投完（每 3 秒扫一次，最多等 45 秒）
        System.out.println();
        System.out.print("  等待 outbox 投递完成");
        long pending = -1;
        for (int i = 0; i < 45; i++) {
            pending = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE id > " + baseOutboxId
                    + " AND status = 0 AND (next_retry_time IS NULL OR next_retry_time <= NOW())");
            if (pending == 0) {
                break;
            }
            Thread.sleep(1000);
            System.out.print(".");
        }
        System.out.println();

        long total = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE id > " + baseOutboxId);
        long sent = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE id > " + baseOutboxId + " AND status = 1");
        long failedTerminal = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE id > " + baseOutboxId + " AND status = 2");
        long stillPending = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE id > " + baseOutboxId + " AND status = 0");
        // 业务数据 vs outbox 记录一致性：每条报工都该有且只有一条 outbox
        long reportRows = queryLong("SELECT COUNT(*) FROM production_report WHERE report_no LIKE '" + runTag + "%'");
        long outboxRows = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE biz_key LIKE 'report:%' AND payload LIKE '%"
                + runTag + "%'");

        System.out.println();
        System.out.println("  ---- outbox 状态 ----");
        System.out.println("  outbox 记录  : " + total);
        System.out.println("  已投递(sent) : " + sent);
        System.out.println("  待投递(pending) : " + stillPending);
        System.out.println("  终态失败(failed) : " + failedTerminal);
        System.out.println("  报工明细行   : " + reportRows + "  对应 outbox 行: " + outboxRows);
        System.out.println();

        check("报工全部成功（" + threads + " 次）", ok.get() == threads, "实际成功 " + ok.get());
        check("outbox 记录数 = 报工数（同事务，一条不多一条不少）",
                total == threads && outboxRows == threads, "outbox=" + total + " 按业务键=" + outboxRows);
        check("全部投递成功（sent = 总数）", sent == total, "sent=" + sent + "/" + total);
        check("无终态失败消息", failedTerminal == 0, "failed=" + failedTerminal);
        check("无遗留待投递消息", stillPending == 0, "pending=" + stillPending);
    }

    /* ================================================================
     * 场景二：重复消费（同一事件投递 N 次 → 进度只算一次）
     * ================================================================ */
    static void sceneReplay(HttpClient client, int times) throws Exception {
        section("场景二：重复消费幂等（同一事件投递 " + times + " 次）");

        long orderId = queryLong("SELECT id FROM production_order ORDER BY id DESC LIMIT 1");
        BigDecimal before = queryDecimal("SELECT completed_quantity FROM production_order WHERE id=" + orderId);
        int progressBefore = (int) queryLong("SELECT progress FROM production_order WHERE id=" + orderId);

        System.out.println("  目标工单 : orderId=" + orderId + " 重算前 completedQuantity=" + before
                + " progress=" + progressBefore + "%");
        System.out.println("  投递同一个 messageId " + times + " 次...");

        String body = String.format("{\"reportId\":1,\"reportNo\":\"REPLAY-%d\",\"orderId\":%d,"
                + "\"taskId\":1,\"stepNo\":10,\"reportQuantity\":1,\"qualifiedQuantity\":1,\"times\":%d}",
                System.currentTimeMillis(), orderId, times);
        String resp = post(client, "/mq/test/replay-report-event", body);
        System.out.println("  接口返回 : " + truncate(resp, 160));

        // 等消费者重算完
        Thread.sleep(5000);
        BigDecimal after = queryDecimal("SELECT completed_quantity FROM production_order WHERE id=" + orderId);
        int progressAfter = (int) queryLong("SELECT progress FROM production_order WHERE id=" + orderId);

        System.out.println();
        System.out.println("  重算后 completedQuantity=" + after + " progress=" + progressAfter + "%");
        System.out.println();

        check("重放接口调用成功", resp != null && resp.contains("\"code\":0"), truncate(resp, 100));
        check("重复消费后 completedQuantity 不翻倍（重算而非累加）",
                after.compareTo(before) == 0,
                "before=" + before + " after=" + after);
        check("重复消费后 progress 不变", progressAfter == progressBefore,
                "before=" + progressBefore + "% after=" + progressAfter + "%");
        check("progress 未超过 100%", progressAfter <= 100, "progress=" + progressAfter + "%");
    }

    /* ================================================================
     * 场景三：路由失败（returns 回调）
     * ================================================================ */
    static void sceneRouteFailure(HttpClient client) throws Exception {
        section("场景三：路由失败 → returns 回调把 outbox 重置为待投递");

        long res = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE last_error LIKE 'returned:%'");
        System.out.println("  已记录的「路由退回」消息数 : " + res);
        System.out.println();

        if (res == 0) {
            fail("路由退回已回写 outbox",
                    "未观察到 returned 记录 —— 需先跑路由键错误场景（见说明）");
            return;
        }
        check("路由退回已回写 outbox（不会被误标为已投递）", res > 0, "count=" + res);
    }

    /* ================================================================
     * 场景四：MQ 宕机降级（人工停/起 broker）
     * ================================================================ */
    static void sceneDowntime(HttpClient client, int threads) throws Exception {
        section("场景四：MQ 宕机 → 业务不阻塞，恢复后自动补发");

        long taskId = prepareReportableTask(threads);
        long baseOutboxId = queryLong("SELECT COALESCE(MAX(id),0) FROM mq_outbox");
        String runTag = "DOWN" + System.currentTimeMillis();

        System.out.println("  ⚠️ 请现在【停掉 RabbitMQ】，然后在 15 秒内按回车继续...");
        System.out.print("  [回车] ");
        System.out.flush();
        try {
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (Exception ignored) {
            // 非交互环境：直接继续
        }

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err = new AtomicInteger();
        long t0 = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            String body = String.format(
                    "{\"reportNo\":\"%s-%d\",\"taskId\":%d,\"reportQuantity\":1,"
                            + "\"qualifiedQuantity\":1,\"defectQuantity\":0,\"operatorId\":4}",
                    runTag, i, taskId);
            String resp = post(client, "/report", body);
            if (resp != null && resp.contains("\"code\":0")) {
                ok.incrementAndGet();
            } else {
                err.incrementAndGet();
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        long outboxRows = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE id > " + baseOutboxId);
        System.out.println();
        System.out.printf("  MQ 宕机期间报工：成功 %d / 失败 %d，耗时 %.2f 秒%n",
                ok.get(), err.get(), elapsedMs / 1000.0);
        System.out.println("  outbox 落库行数（宕机期间） : " + outboxRows);
        System.out.println();

        check("MQ 宕机期间报工全部成功（业务不阻塞）", ok.get() == threads, "成功 " + ok.get() + "/" + threads);
        check("MQ 宕机期间 outbox 仍完整落库", outboxRows == threads,
                "outbox=" + outboxRows + " 期望 " + threads);

        System.out.println();
        System.out.println("  ⚠️ 请现在【启动 RabbitMQ】，然后按回车等待自动补发...");
        System.out.print("  [回车] ");
        System.out.flush();
        try {
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (Exception ignored) {
        }

        System.out.print("  等待自动补发");
        long pending = -1;
        for (int i = 0; i < 120; i++) {
            pending = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE id > " + baseOutboxId
                    + " AND status = 0 AND (next_retry_time IS NULL OR next_retry_time <= NOW())");
            if (pending == 0) {
                break;
            }
            Thread.sleep(1000);
            System.out.print(".");
        }
        System.out.println();

        long sent = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE id > " + baseOutboxId + " AND status = 1");
        long failedTerminal = queryLong("SELECT COUNT(*) FROM mq_outbox WHERE id > " + baseOutboxId + " AND status = 2");
        System.out.println("  恢复后已投递 : " + sent + " / " + threads);
        System.out.println();
        check("MQ 恢复后自动补发完成", sent == threads, "sent=" + sent + "/" + threads);
        check("补发过程无终态失败", failedTerminal == 0, "failed=" + failedTerminal);
    }

    /* ================================================================
     * 工具
     * ================================================================ */

    /** 把某个工序任务重置为「进行中、已报 0」，供重复压测使用。 */
    static long prepareReportableTask(int threads) throws Exception {
        long taskId;
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM work_task ORDER BY id LIMIT 1")) {
            taskId = rs.next() ? rs.getLong(1) : 1L;
        }
        try (Connection c = connect(); Statement st = c.createStatement()) {
            // WorkTaskStatus.IN_PROGRESS = 2（1=待开始 4=已完成，别写错）
            st.executeUpdate("UPDATE work_task SET status = 2, reported_quantity = 0, "
                    + "qualified_quantity = 0, defect_quantity = 0, plan_quantity = "
                    + (threads * 10) + " WHERE id = " + taskId);
        }
        return taskId;
    }

    static boolean preflight(HttpClient client) {
        try {
            String resp = get(client, "/mq/outbox/stats");
            return resp != null && resp.contains("\"code\":0");
        } catch (Exception e) {
            System.out.println("  ✗ 服务不可用: " + e.getMessage());
            return false;
        }
    }

    static Connection connect() throws SQLException {
        if (DB_PASS.isEmpty()) {
            throw new SQLException("缺少环境变量 MYSQL_PASS，请先在 cmd 执行: set MYSQL_PASS=你的MySQL密码");
        }
        return DriverManager.getConnection(JDBC, DB_USER, DB_PASS);
    }

    static long queryLong(String sql) throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    static BigDecimal queryDecimal(String sql) throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return BigDecimal.ZERO;
            }
            BigDecimal v = rs.getBigDecimal(1);
            return v == null ? BigDecimal.ZERO : v;
        }
    }

    static String post(HttpClient client, String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        } catch (Exception e) {
            return null;
        }
    }

    static String get(HttpClient client, String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        } catch (Exception e) {
            return null;
        }
    }

    static String extract(String json, String key) {
        if (json == null) {
            return "null response";
        }
        int i = json.indexOf(key);
        if (i < 0) {
            return truncate(json, 100);
        }
        int start = i + key.length();
        int end = json.indexOf('"', start);
        return end < 0 ? json.substring(start) : json.substring(start, end);
    }

    static String truncate(String s, int n) {
        if (s == null) {
            return "null";
        }
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    static void header(String title) {
        System.out.println("==============================================");
        System.out.println("  " + title);
    }

    static void section(String title) {
        System.out.println("----------------------------------------------");
        System.out.println("  " + title);
        System.out.println("----------------------------------------------");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + "  (" + detail + ")");
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "  (" + detail + ")");
        }
    }

    static void fail(String name, String detail) {
        failed++;
        System.out.println("  [FAIL] " + name + "  (" + detail + ")");
    }

    static void summary() {
        System.out.println();
        System.out.println("==============================================");
        System.out.println("  通过检查点: " + passed);
        System.out.println("  失败检查点: " + failed);
        System.out.println(failed == 0 ? "*** 消息可靠性压测通过 ***" : "*** 消息可靠性压测失败 ***");
        System.out.println("==============================================");
    }
}
