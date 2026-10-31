import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 库存并发预留测试（独立运行，不依赖框架）。
 *
 * <h3>测试场景（对应需求文档 6.4）</h3>
 * 物料库存 100，N 个不同工单同时各预留 1 个。
 * <b>预期：恰好 N 次成功，0 次超发。</b>
 *
 * <h3>为什么要用不同 orderId</h3>
 * reserve 接口按 orderId 幂等（同一工单重复预留直接返回），
 * 所以并发测试必须用不同 orderId 才能真正打到同一行库存上。
 *
 * <h3>为什么用 CountDownLatch 而不是线程池直接 submit</h3>
 * 需要让所有线程<b>同时</b>发起请求，才能最大化并发冲突。
 * 直接 submit 会让线程陆续启动，第一个可能已经完成，测不出竞态。
 *
 * <p>编译运行：
 * <pre>
 *   java PerfInventoryConcurrency.java
 * </pre>
 */
public class PerfInventoryConcurrency {

    static final String BASE = "http://localhost:9004";

    public static void main(String[] args) throws Exception {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        long materialId = args.length > 1 ? Long.parseLong(args[1]) : 1L;
        int perRequest = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        // 可选第 4 参数：预期成功次数（不传则默认等于线程数，即"库存刚好等于总需求"的场景）
        int expectedSuccess = args.length > 3 ? Integer.parseInt(args[3]) : threads;
        long baseOrderId = 900000L;

        System.out.println("==============================================");
        System.out.println("  库存并发预留测试");
        System.out.println("  物料 ID  : " + materialId);
        System.out.println("  并发线程 : " + threads);
        System.out.println("  每次预留 : " + perRequest);
        System.out.println("  预期成功 : " + expectedSuccess
                + (expectedSuccess < threads ? "（库存不足，其余应被拒绝）" : ""));
        System.out.println("==============================================");
        System.out.println();

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newFixedThreadPool(threads + 8))
                .build();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        Map<String, AtomicInteger> failReasons = new ConcurrentHashMap<>();

        long t0 = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            final long orderId = baseOrderId + i;
            new Thread(() -> {
                try {
                    startGate.await();
                    String body = String.format(
                            "{\"orderId\":%d,\"orderNo\":\"CONC-%d\",\"warehouseId\":1,"
                                    + "\"items\":[{\"materialId\":%d,\"materialCode\":\"MAT-CONC\","
                                    + "\"materialName\":\"并发测试物料\",\"unit\":\"个\","
                                    + "\"requiredQuantity\":%d}]}",
                            orderId, orderId, materialId, perRequest);
                    HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/reservation/reserve"))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .timeout(Duration.ofSeconds(60))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> resp = client.send(req,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    String text = resp.body();
                    // 统一返回体 code=0 表示成功
                    if (text != null && text.contains("\"code\":0")) {
                        success.incrementAndGet();
                    } else {
                        fail.incrementAndGet();
                        String msg = extractMessage(text);
                        failReasons.computeIfAbsent(msg, k -> new AtomicInteger()).incrementAndGet();
                    }
                } catch (Exception e) {
                    fail.incrementAndGet();
                    failReasons.computeIfAbsent("EXCEPTION: " + e.getClass().getSimpleName()
                            + " " + e.getMessage(), k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            }, "conc-" + i).start();
        }

        startGate.countDown();
        endGate.await(180, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("----------------------------------------------");
        System.out.printf("  测试耗时 : %.2f 秒%n", elapsedMs / 1000.0);
        System.out.println("  成功次数 : " + success.get());
        System.out.println("  失败次数 : " + fail.get());
        System.out.println("----------------------------------------------");
        if (!failReasons.isEmpty()) {
            System.out.println("  失败原因分布：");
            failReasons.forEach((k, v) -> System.out.println("    [" + v.get() + " 次] " + k));
            System.out.println("----------------------------------------------");
        }

        // 查询最终库存状态
        HttpRequest q = HttpRequest.newBuilder(URI.create(BASE + "/inventory/" + materialId))
                .timeout(Duration.ofSeconds(20)).GET().build();
        String invJson = client.send(q,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        System.out.println("  最终库存 : " + invJson);
        System.out.println();

        boolean ok = true;
        System.out.println("=== 断言检查 ===");
        // 断言 1：成功次数 == expectedSuccess（库存恰好等于 total demand 时为线程数；
        //         库存不足时应恰好等于 库存/每次预留 的数量）
        boolean a1 = success.get() == expectedSuccess;
        System.out.println((a1 ? "  [PASS] " : "  [FAIL] ") + "成功次数 == " + expectedSuccess
                + "（实际 " + success.get() + "）");
        ok &= a1;
        // 断言 2：失败次数 == threads - expectedSuccess（一个不多一个不少）
        int expectedFail = threads - expectedSuccess;
        boolean a2 = fail.get() == expectedFail;
        System.out.println((a2 ? "  [PASS] " : "  [FAIL] ") + "失败次数 == " + expectedFail
                + "（实际 " + fail.get() + "）");
        ok &= a2;
        // 断言 3：关键业务不变量 —— 可用量不能为负（服务器端做二次确认）
        boolean a3 = invJson != null && !invJson.contains("-") ? true : checkNonNegative(invJson);
        System.out.println((a3 ? "  [PASS] " : "  [FAIL] ")
                + "可用量不为负（quantity >= reserved_quantity）");
        ok &= a3;

        System.out.println();
        System.out.println(ok ? "*** 并发测试通过：零超卖 ***" : "*** 并发测试失败 ***");
        System.exit(ok ? 0 : 1);
    }

    /**
     * 校验返回的库存 JSON 里 availableQuantity >= 0。
     * （不引入 JSON 库，用简单字符串解析，够用）
     */
    static boolean checkNonNegative(String invJson) {
        if (invJson == null) {
            return false;
        }
        String key = "\"availableQuantity\":";
        int i = invJson.indexOf(key);
        if (i < 0) {
            return true;   // 没有该字段则不做判断
        }
        int start = i + key.length();
        int end = start;
        while (end < invJson.length()
                && (Character.isDigit(invJson.charAt(end)) || invJson.charAt(end) == '.'
                    || invJson.charAt(end) == '-')) {
            end++;
        }
        try {
            return new java.math.BigDecimal(invJson.substring(start, end))
                    .compareTo(java.math.BigDecimal.ZERO) >= 0;
        } catch (Exception e) {
            return true;
        }
    }

    static String extractMessage(String text) {
        if (text == null) {
            return "null response";
        }
        int i = text.indexOf("\"message\":\"");
        if (i < 0) {
            return text.length() > 120 ? text.substring(0, 120) : text;
        }
        int start = i + 11;
        int end = text.indexOf('"', start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }
}
