package com.smart.quality.service;

import com.smart.common.constant.CommonConstants;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.quality.dto.QualityDtos.BatchVO;
import com.smart.quality.dto.QualityDtos.TraceNode;
import com.smart.quality.dto.QualityDtos.TraceResultVO;
import com.smart.quality.entity.BatchRelation;
import com.smart.quality.entity.ProductBatch;
import com.smart.quality.mapper.BatchRelationMapper;
import com.smart.quality.mapper.ProductBatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 批次追溯服务（第 6 周任务 6.2 / 6.5 / 6.6）—— MES 最有辨识度的能力。
 *
 * <h3>数据结构：有向无环图（DAG）</h3>
 * <pre>
 *   物料批次(MB001) ──消耗──> 产品批次(PB001)
 *   物料批次(MB002) ──消耗──> 产品批次(PB001)
 *   物料批次(MB001) ──消耗──> 产品批次(PB002)   ← 一个原料批次可用于多个工单
 * </pre>
 *
 * <h3>两个方向的遍历</h3>
 * <ul>
 *   <li><b>反向追溯</b>（成品→原料）：从 target 沿 source 方向走。
 *       用途：客户投诉某台电表 → 查出用了哪些料、哪个供应商 → 定位根因</li>
 *   <li><b>正向追溯</b>（原料→成品）：从 source 沿 target 方向走。
 *       用途：某供应商的芯片有缺陷 → 查出所有用了该批次的成品 → <b>精准召回</b></li>
 * </ul>
 *
 * <h3>三个必须处理的工程问题</h3>
 * <ol>
 *   <li><b>防环</b>：业务上不该有环，但数据可能配错。遍历必须用 {@code visited}，
 *       否则死循环把服务打挂</li>
 *   <li><b>深度限制</b>：最多 {@link CommonConstants#MAX_TREE_DEPTH} 层，
 *       防止异常数据导致查询爆炸、响应超大</li>
 *   <li><b>避免 N+1</b>：一次性查出全部关系边，再在内存里 BFS，
 *       而不是每层发一次 SQL（"追溯查询慢"的典型原因）</li>
 * </ol>
 *
 * <p>为什么用关系表 DAG 而不是图数据库：数据量小，关系表 + BFS 完全够用。
 * <b>为了技术栈而引入 Neo4j 是减分项</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceService {

    private final ProductBatchMapper batchMapper;
    private final BatchRelationMapper relationMapper;

    /* ==================== 反向追溯（成品 → 原料） ==================== */

    /**
     * 反向追溯：给定成品批次号，查出全部上游物料批次及供应商。
     */
    public TraceResultVO backward(String batchNo, int maxDepth) {
        ProductBatch root = requireBatch(batchNo);
        int limit = maxDepth <= 0 ? CommonConstants.MAX_TREE_DEPTH : maxDepth;

        // 一次性查出全图边，按 target 分组（target → 它的上游边）
        List<BatchRelation> allEdges = relationMapper.selectAllActive();
        Map<Long, List<BatchRelation>> incoming = new HashMap<>();
        for (BatchRelation e : allEdges) {
            if (e.getTargetBatchId() != null) {
                incoming.computeIfAbsent(e.getTargetBatchId(), k -> new ArrayList<>()).add(e);
            }
        }

        Walk<BatchRelation> walk = walk(root.getId(), incoming::get,
                BatchRelation::getSourceBatchId, limit);
        Set<Long> emitted = new HashSet<>();
        List<TraceNode> tree = buildTree(walk.childrenOf.get(root.getId()), walk, 1, emitted);

        TraceResultVO vo = new TraceResultVO();
        vo.setDirection("BACKWARD");
        vo.setRoot(toBatchVO(root));
        vo.setTree(tree);
        vo.setTotalNodes(walk.visited.size());
        vo.setMaxDepth(walk.maxDepth);
        vo.setTruncated(walk.truncated);
        vo.setMessage(String.format("反向追溯完成：共涉及 %d 个批次（含起点）%s",
                walk.visited.size(),
                walk.truncated ? "；已达深度上限 " + limit + "，结果可能不完整" : ""));
        log.info("[反向追溯] batchNo={} 涉及批次={} 深度={} truncated={}",
                batchNo, walk.visited.size(), walk.maxDepth, walk.truncated);
        return vo;
    }

    /* ==================== 正向追溯（原料 → 成品） ==================== */

    /**
     * 正向追溯：给定物料批次号，查出全部下游产品批次（精准召回的技术基础）。
     */
    public TraceResultVO forward(String batchNo, int maxDepth) {
        ProductBatch root = requireBatch(batchNo);
        int limit = maxDepth <= 0 ? CommonConstants.MAX_TREE_DEPTH : maxDepth;

        List<BatchRelation> allEdges = relationMapper.selectAllActive();
        Map<Long, List<BatchRelation>> outgoing = new HashMap<>();
        for (BatchRelation e : allEdges) {
            if (e.getSourceBatchId() != null && e.getTargetBatchId() != null) {
                outgoing.computeIfAbsent(e.getSourceBatchId(), k -> new ArrayList<>()).add(e);
            }
        }

        Walk<BatchRelation> walk = walk(root.getId(), outgoing::get,
                BatchRelation::getTargetBatchId, limit);
        Set<Long> emitted = new HashSet<>();
        List<TraceNode> tree = buildTree(walk.childrenOf.get(root.getId()), walk, 1, emitted);

        TraceResultVO vo = new TraceResultVO();
        vo.setDirection("FORWARD");
        vo.setRoot(toBatchVO(root));
        vo.setTree(tree);
        vo.setTotalNodes(walk.visited.size());
        vo.setMaxDepth(walk.maxDepth);
        vo.setTruncated(walk.truncated);
        vo.setMessage(String.format("正向追溯完成：该批次影响 %d 个下游批次%s",
                Math.max(0, walk.visited.size() - 1),
                walk.truncated ? "；已达深度上限 " + limit + "，结果可能不完整" : ""));
        log.info("[正向追溯] batchNo={} 下游批次={} 深度={}",
                batchNo, Math.max(0, walk.visited.size() - 1), walk.maxDepth);
        return vo;
    }

    /* ==================== 统一的图遍历核心 ==================== */

    /**
     * 图遍历（BFS，逐层展开）。
     *
     * <h3>防环机制</h3>
     * {@code visited} 是<b>全局</b>集合：一个批次只会被展开一次。
     * 为什么用全局而不是"路径相关"：追溯结果本质是"这个成品受哪些批次影响"的集合，
     * 同一个物料批次被两条路径到达时，去重展示一次即可（否则树会指数膨胀）。
     * 遇到已访问节点时不再下探 —— 这同时天然阻断了环。
     *
     * @param startId      起点批次
     * @param edgeLookup   取某节点的边
     * @param nextSelector 从边取"下一个节点 id"
     * @param maxDepth     最大深度
     */
    private <E> Walk<E> walk(Long startId,
                             java.util.function.Function<Long, List<E>> edgeLookup,
                             java.util.function.Function<E, Long> nextSelector,
                             int maxDepth) {
        Walk<E> walk = new Walk<>();
        walk.visited.add(startId);
        walk.maxDepth = 0;

        // 当前层：节点 id → 入边（父边，用于记录消耗数量）
        Map<Long, List<E>> currentLevel = new HashMap<>();
        currentLevel.put(startId, List.of());
        walk.childrenOf.put(startId, new ArrayList<>());

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (currentLevel.isEmpty()) {
                break;
            }
            // 收集本层所有边，按"下一节点"分组
            Map<Long, List<E>> nextLevel = new HashMap<>();
            // 记录 parentOf：子节点 id → (父节点 id, 边)
            Map<Long, List<EdgeRef<E>>> refsByChild = new HashMap<>();

            for (Long nodeId : currentLevel.keySet()) {
                List<E> edges = edgeLookup.apply(nodeId);
                if (edges == null || edges.isEmpty()) {
                    continue;
                }
                for (E edge : edges) {
                    Long next = nextSelector.apply(edge);
                    if (next == null) {
                        continue;
                    }
                    refsByChild.computeIfAbsent(next, k -> new ArrayList<>())
                            .add(new EdgeRef<>(nodeId, edge));
                    if (!walk.visited.contains(next)) {
                        nextLevel.computeIfAbsent(next, k -> new ArrayList<>());
                    }
                }
            }

            if (refsByChild.isEmpty()) {
                break;
            }

            // 记录每个新节点的父边关系
            for (Map.Entry<Long, List<EdgeRef<E>>> entry : refsByChild.entrySet()) {
                Long childId = entry.getKey();
                for (EdgeRef<E> ref : entry.getValue()) {
                    walk.childrenOf.computeIfAbsent(ref.parentId, k -> new ArrayList<>())
                            .add(new ChildRef<>(childId, ref.edge));
                }
            }

            // 深度超限：本层节点仍然要显示（只是不再向下展开），
            // 所以必须先加入 visited，否则 buildTree 会把它们当作不可见节点跳过，
            // 导致"深度=1 时结果树为空"这种错误。
            if (depth == maxDepth) {
                walk.visited.addAll(nextLevel.keySet());
                walk.maxDepth = depth;
                // 若本层节点自身还有出边，说明结果确实不完整 → 标记截断
                for (Long nodeId : nextLevel.keySet()) {
                    List<E> deeper = edgeLookup.apply(nodeId);
                    if (deeper != null && !deeper.isEmpty()) {
                        boolean hasDeeperUnvisited = false;
                        for (E e : deeper) {
                            Long n = nextSelector.apply(e);
                            if (n != null && !walk.visited.contains(n)) {
                                hasDeeperUnvisited = true;
                                break;
                            }
                        }
                        if (hasDeeperUnvisited) {
                            walk.truncated = true;
                            break;
                        }
                    }
                }
                break;
            }

            // 标记已访问
            walk.visited.addAll(nextLevel.keySet());
            currentLevel = nextLevel;
            walk.maxDepth = depth;
        }
        return walk;
    }

    /**
     * 把 BFS 得到的父子关系转成树形节点。
     *
     * <p>⚠️ 必须传 {@code emitted} 集合做环路防护：
     * {@code childrenOf} 里可能因为数据配错而出现 A→B→A 的环（例如两个批次互相引用），
     * 若无条件递归会直接 StackOverflow 把服务打挂。
     * 这里"一个批次在结果树中只出现一次"，遇到重复即剪枝。
     */
    private <E> List<TraceNode> buildTree(List<ChildRef<E>> children, Walk<E> walk,
                                          int level, Set<Long> emitted) {
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        List<TraceNode> nodes = new ArrayList<>();
        for (ChildRef<E> ref : children) {
            Long childId = ref.childId;
            if (!walk.visited.contains(childId)) {
                continue;
            }
            // 环路防护：已经在结果树中出现过的批次不再重复展开
            if (!emitted.add(childId)) {
                log.warn("[追溯] 检测到重复节点/环路，已剪枝 batchId={}（批次图不应有环，请检查数据）",
                        childId);
                continue;
            }
            ProductBatch batch = batchMapper.selectById(childId);
            if (batch == null) {
                continue;
            }
            TraceNode node = new TraceNode();
            node.setLevel(level);
            node.setBatchId(batch.getId());
            node.setBatchNo(batch.getBatchNo());
            node.setBatchType(batch.getBatchType());
            node.setBatchTypeDesc(batchTypeDesc(batch.getBatchType()));
            fillNames(node, batch);
            node.setBatchQuantity(batch.getQuantity());
            node.setEdgeQuantity(edgeQuantity(ref.edge));
            node.setSupplierId(batch.getSupplierId());
            node.setSupplierName(batch.getSupplierName());
            node.setProductionOrderId(batch.getProductionOrderId());
            node.setOrderNo(batch.getOrderNo());
            node.setInboundTime(batch.getInboundTime());
            node.setQualityStatus(batch.getQualityStatus());

            List<TraceNode> sub = buildTree(walk.childrenOf.get(childId), walk, level + 1, emitted);
            node.setChildren(sub);
            node.setHasChildren(!sub.isEmpty());
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * 从边对象里取"消耗数量"（BatchRelation 上有 quantity）。
     */
    @SuppressWarnings("unchecked")
    private BigDecimal edgeQuantity(Object edge) {
        if (edge instanceof BatchRelation r) {
            return r.getQuantity();
        }
        return null;
    }

    private void fillNames(TraceNode node, ProductBatch batch) {
        boolean isMaterial = batch.getBatchType() != null
                && batch.getBatchType() == ProductBatch.TYPE_MATERIAL;
        node.setCode(isMaterial ? batch.getMaterialCode() : batch.getProductCode());
        node.setName(isMaterial ? batch.getMaterialName() : batch.getProductName());
    }

    /* ==================== 记录批次关系 ==================== */

    /**
     * 记录一条批次关系（领料消耗 / 完工产出）。
     *
     * <p>⚠️ 必须在领料/完工的<b>同一事务</b>内调用，
     * 否则会出现"领料成功但追溯关系漏记" —— 追溯链就断了。
     */
    public BatchRelation recordRelation(String sourceBatchNo, String targetBatchNo,
                                        Long productionOrderId, String orderNo,
                                        BigDecimal quantity, String relationType) {
        ProductBatch source = batchMapper.selectByBatchNo(sourceBatchNo);
        if (source == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "源批次不存在: " + sourceBatchNo);
        }
        ProductBatch target = null;
        if (targetBatchNo != null && !targetBatchNo.isBlank()) {
            target = batchMapper.selectByBatchNo(targetBatchNo);
            if (target == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "目标批次不存在: " + targetBatchNo);
            }
            if (source.getId().equals(target.getId())) {
                throw new BusinessException("源批次与目标批次不能相同（会形成自环）");
            }
        }

        BatchRelation rel = new BatchRelation();
        rel.setSourceBatchId(source.getId());
        rel.setSourceBatchNo(source.getBatchNo());
        rel.setTargetBatchId(target == null ? null : target.getId());
        rel.setTargetBatchNo(target == null ? null : target.getBatchNo());
        rel.setProductionOrderId(productionOrderId);
        rel.setOrderNo(orderNo);
        rel.setQuantity(quantity == null ? BigDecimal.ZERO : quantity);
        rel.setRelationType(relationType == null ? "CONSUME" : relationType);
        rel.setIsReversed(0);
        relationMapper.insert(rel);
        log.info("[批次关系] {} --({})--> {} qty={} orderId={}",
                sourceBatchNo, rel.getRelationType(),
                target == null ? "(待关联)" : targetBatchNo, rel.getQuantity(), productionOrderId);
        return rel;
    }

    /**
     * 完工时把"领料阶段记录的待关联关系"回填到产品批次。
     *
     * <p>为什么需要"待关联"中间态：领料时产品批次还不存在（要等完工才有），
     * 所以 relation 的 target 暂时为空。完工生成产品批次后按 productionOrderId 回填。
     */
    public int bindOrderConsumptionsToProductBatch(Long productionOrderId, String productBatchNo) {
        ProductBatch target = batchMapper.selectByBatchNo(productBatchNo);
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "产品批次不存在: " + productBatchNo);
        }
        List<BatchRelation> pending = new ArrayList<>();
        for (BatchRelation r : relationMapper.selectByOrderId(productionOrderId)) {
            if (r.getTargetBatchId() == null) {
                pending.add(r);
            }
        }
        for (BatchRelation r : pending) {
            r.setTargetBatchId(target.getId());
            r.setTargetBatchNo(target.getBatchNo());
            relationMapper.updateById(r);
        }
        log.info("[批次关系回填] orderId={} 关联 {} 条消耗关系 -> 产品批次 {}",
                productionOrderId, pending.size(), productBatchNo);
        return pending.size();
    }

    /* ==================== 工具 ==================== */

    public ProductBatch requireBatch(String batchNo) {
        ProductBatch b = batchMapper.selectByBatchNo(batchNo);
        if (b == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "批次不存在: " + batchNo);
        }
        return b;
    }

    public BatchVO toBatchVO(ProductBatch b) {
        if (b == null) {
            return null;
        }
        BatchVO vo = new BatchVO();
        vo.setId(b.getId());
        vo.setBatchNo(b.getBatchNo());
        vo.setBatchType(b.getBatchType());
        vo.setBatchTypeDesc(batchTypeDesc(b.getBatchType()));
        vo.setMaterialId(b.getMaterialId());
        vo.setMaterialCode(b.getMaterialCode());
        vo.setMaterialName(b.getMaterialName());
        vo.setProductId(b.getProductId());
        vo.setProductCode(b.getProductCode());
        vo.setProductName(b.getProductName());
        vo.setProductionOrderId(b.getProductionOrderId());
        vo.setOrderNo(b.getOrderNo());
        vo.setQuantity(b.getQuantity());
        vo.setQualifiedQuantity(b.getQualifiedQuantity());
        vo.setDefectQuantity(b.getDefectQuantity());
        vo.setSupplierId(b.getSupplierId());
        vo.setSupplierName(b.getSupplierName());
        vo.setInboundTime(b.getInboundTime());
        vo.setQualityStatus(b.getQualityStatus());
        vo.setStatus(b.getStatus());
        return vo;
    }

    public static String batchTypeDesc(Integer type) {
        if (type == null) {
            return "未知";
        }
        return type == ProductBatch.TYPE_MATERIAL ? "物料批次" : "产品批次";
    }

    /* ==================== 内部数据结构 ==================== */

    /** 一次遍历的中间结果 */
    private static final class Walk<E> {
        final Set<Long> visited = new HashSet<>();
        final Map<Long, List<ChildRef<E>>> childrenOf = new HashMap<>();
        int maxDepth;
        boolean truncated;
    }

    private record ChildRef<E>(Long childId, E edge) {
    }

    private record EdgeRef<E>(Long parentId, E edge) {
    }
}
