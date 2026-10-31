package com.smart.product.service;

import com.smart.common.constant.CommonConstants;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.product.dto.ProductDtos.BomCreateDTO;
import com.smart.product.dto.ProductDtos.BomItemDTO;
import com.smart.product.dto.ProductDtos.BomItemVO;
import com.smart.product.dto.ProductDtos.BomRequirementItem;
import com.smart.product.dto.ProductDtos.BomTreeNode;
import com.smart.product.dto.ProductDtos.BomVO;
import com.smart.product.entity.Bom;
import com.smart.product.entity.BomItem;
import com.smart.product.entity.Material;
import com.smart.product.entity.Product;
import com.smart.product.enums.VersionStatus;
import com.smart.product.mapper.BomItemMapper;
import com.smart.product.mapper.BomMapper;
import com.smart.product.mapper.MaterialMapper;
import com.smart.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * BOM 服务（第 2 周任务 2.3 / 2.4，本周最难，必须做对）。
 *
 * <h3>版本化规则（三条铁律）</h3>
 * <ol>
 *   <li>新建 BOM 状态为 DRAFT（草稿）</li>
 *   <li>审核后置为 ACTIVE（生效），<b>同一事务内</b>把同产品其他 ACTIVE 版本置为 INACTIVE</li>
 *   <li>已 ACTIVE 的 BOM <b>不允许修改明细</b>，要改必须新建版本</li>
 * </ol>
 *
 * <p>为什么允许改已生效 BOM 是灾难：生产工单会锁定 bom_id。
 * 如果 BOM 生效后被改，历史工单当时的物料需求就无法追溯了 —— 这在制造业是审计事故。
 *
 * <h3>BOM 树展开（任务 2.4）</h3>
 * 递归展开多层 BOM，并做两件事：
 * <ul>
 *   <li><b>环路检测</b>：用 Set 记录路径上的 productId，发现重复立刻报错而不是栈溢出</li>
 *   <li><b>深度限制</b>：最多 {@link CommonConstants#MAX_TREE_DEPTH} 层，防止异常数据把树撑爆</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BomService {

    private final BomMapper bomMapper;
    private final BomItemMapper bomItemMapper;
    private final ProductMapper productMapper;
    private final MaterialMapper materialMapper;

    /* ==================== 查询 ==================== */

    /**
     * 查某产品当前生效的 BOM。没有则抛 {@link ErrorCode#BOM_NOT_EFFECTIVE}。
     *
     * <p>生产服务创建工单时调用（Feign），所以错误码要能表达"未配置生效 BOM"。
     */
    public Bom requireActiveBom(Long productId) {
        Bom bom = bomMapper.selectActiveByProductId(productId);
        if (bom == null) {
            throw new BusinessException(ErrorCode.BOM_NOT_EFFECTIVE,
                    "产品[id=" + productId + "]未配置生效的 BOM，请先创建并审核生效");
        }
        return bom;
    }

    public List<Bom> listByProduct(Long productId) {
        return bomMapper.selectByProductId(productId);
    }

    public BomVO getBomDetail(Long bomId) {
        Bom bom = bomMapper.selectById(bomId);
        if (bom == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "BOM 不存在: " + bomId);
        }
        return toVO(bom);
    }

    private BomVO toVO(Bom bom) {
        BomVO vo = new BomVO();
        vo.setId(bom.getId());
        vo.setProductId(bom.getProductId());
        vo.setBomVersion(bom.getBomVersion());
        vo.setStatus(bom.getStatus());
        vo.setStatusDesc(VersionStatus.descOf(bom.getStatus()));
        vo.setEffectiveDate(bom.getEffectiveDate());
        vo.setRemark(bom.getRemark());

        Product p = productMapper.selectById(bom.getProductId());
        if (p != null) {
            vo.setProductCode(p.getProductCode());
            vo.setProductName(p.getProductName());
        }

        List<BomItem> items = bomItemMapper.selectByBomId(bom.getId());
        List<BomItemVO> itemVOs = new ArrayList<>(items.size());
        if (!items.isEmpty()) {
            List<Long> materialIds = items.stream().map(BomItem::getMaterialId).toList();
            Map<Long, Material> materialMap = materialMapper.selectBatchIds(materialIds).stream()
                    .collect(Collectors.toMap(Material::getId, Function.identity()));
            for (BomItem it : items) {
                BomItemVO iv = new BomItemVO();
                iv.setId(it.getId());
                iv.setMaterialId(it.getMaterialId());
                iv.setQuantity(it.getQuantity());
                iv.setLossRate(it.getLossRate());
                iv.setSeqNo(it.getSeqNo());
                iv.setRemark(it.getRemark());
                Material m = materialMap.get(it.getMaterialId());
                if (m != null) {
                    iv.setMaterialCode(m.getMaterialCode());
                    iv.setMaterialName(m.getMaterialName());
                    iv.setMaterialSpec(m.getSpec());
                    iv.setUnit(m.getUnit());
                }
                itemVOs.add(iv);
            }
        }
        vo.setItems(itemVOs);
        return vo;
    }

    /* ==================== 创建 / 生效 ==================== */

    /**
     * 创建 BOM（草稿状态）。含明细，创建后不允许修改明细。
     */
    @Transactional(rollbackFor = Exception.class)
    public BomVO createBom(BomCreateDTO dto) {
        Product product = productMapper.selectById(dto.getProductId());
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "产品不存在: " + dto.getProductId());
        }

        // 明细校验：同一物料只能出现一次（uk_bom_material 兜底，这里提前给出友好提示）
        Set<Long> seen = new HashSet<>();
        for (BomItemDTO item : dto.getItems()) {
            if (!seen.add(item.getMaterialId())) {
                throw new BusinessException("BOM 明细中物料[id=" + item.getMaterialId()
                        + "]重复出现，请合并为一行");
            }
            if (materialMapper.selectById(item.getMaterialId()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                        "物料不存在: " + item.getMaterialId());
            }
        }

        String version = dto.getBomVersion();
        if (version == null || version.isBlank()) {
            version = nextVersion(dto.getProductId());
        }

        Bom bom = new Bom();
        bom.setProductId(dto.getProductId());
        bom.setBomVersion(version);
        bom.setStatus(VersionStatus.DRAFT.getCode());
        bom.setRemark(dto.getRemark());
        try {
            bomMapper.insert(bom);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_KEY,
                    "该产品已存在版本 " + version + " 的 BOM");
        }

        int seq = 1;
        for (BomItemDTO item : dto.getItems()) {
            BomItem bi = new BomItem();
            bi.setBomId(bom.getId());
            bi.setMaterialId(item.getMaterialId());
            bi.setQuantity(item.getQuantity());
            bi.setLossRate(item.getLossRate() == null ? BigDecimal.ZERO : item.getLossRate());
            bi.setSeqNo(item.getSeqNo() == null ? seq : item.getSeqNo());
            bi.setRemark(item.getRemark());
            bomItemMapper.insert(bi);
            seq++;
        }
        log.info("[BOM 创建] id={} product={} version={} items={}",
                bom.getId(), product.getProductCode(), version, dto.getItems().size());
        return getBomDetail(bom.getId());
    }

    /**
     * 审核生效：草稿 → 生效，并把同产品其他生效版本置为失效。
     *
     * <p>⚠️ 三步必须在<b>同一事务</b>内：
     * <pre>
     *   1. CAS 置目标为 ACTIVE（WHERE status = DRAFT，防并发重复生效）
     *   2. UPDATE 同产品其他版本 status = INACTIVE
     * </pre>
     *
     * <p>为什么不建数据库唯一约束保证"一个产品只有一个 ACTIVE"：
     * MySQL 唯一索引无法表达"仅当 status=2 时唯一"（部分索引），
     * 所以用"同一事务内先全置失效再激活目标"+"CAS 防止并发"来保证。
     */
    @Transactional(rollbackFor = Exception.class)
    public BomVO activateBom(Long bomId, LocalDate effectiveDate) {
        Bom bom = bomMapper.selectById(bomId);
        if (bom == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "BOM 不存在: " + bomId);
        }
        if (bom.getStatus() == null || bom.getStatus() != VersionStatus.DRAFT.getCode()) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "只有草稿状态的 BOM 可以生效，当前状态：" + VersionStatus.descOf(bom.getStatus()));
        }
        if (bomItemMapper.countByBomId(bomId) == 0) {
            throw new BusinessException("BOM 没有明细，无法生效");
        }

        long affected = bomMapper.activateCas(bomId, effectiveDate);
        if (affected != 1) {
            // CAS 失败说明并发下已被别的线程生效
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT,
                    "BOM 状态已被其他操作变更，请刷新后重试");
        }
        long deactivated = bomMapper.deactivateOthers(bom.getProductId(), bomId);
        log.info("[BOM 生效] id={} version={} 顶替失效的旧版本数={}", bomId, bom.getBomVersion(), deactivated);
        return getBomDetail(bomId);
    }

    /**
     * 自动生成版本号：已发布版本数 + 1 → V1 / V2 / V3…
     */
    private String nextVersion(Long productId) {
        int released = bomMapper.countReleasedVersions(productId);
        return "V" + (released + 1);
    }

    /**
     * 删除 BOM（仅草稿可删；已生效版本是历史凭证，不允许删除）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBom(Long bomId) {
        Bom bom = bomMapper.selectById(bomId);
        if (bom == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "BOM 不存在: " + bomId);
        }
        if (bom.getStatus() == null || bom.getStatus() != VersionStatus.DRAFT.getCode()) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "只有草稿状态的 BOM 可以删除（已生效版本是历史凭证）");
        }
        bomItemMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BomItem>()
                .eq(BomItem::getBomId, bomId));
        bomMapper.deleteById(bomId);
        log.info("[BOM 删除] id={}", bomId);
    }

    /* ==================== 需求素材（供生产/库存服务计算） ==================== */

    /**
     * 按 bomId 取物料需求素材（含损耗率）。
     *
     * <p>⚠️ 生产服务必须用<b>工单锁定的 bom_id</b> 调用，而不是产品当前 BOM。
     */
    public List<BomRequirementItem> listRequirementItems(Long bomId) {
        Bom bom = bomMapper.selectById(bomId);
        if (bom == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "BOM 不存在: " + bomId);
        }
        return buildRequirementItems(bomId);
    }

    private List<BomRequirementItem> buildRequirementItems(Long bomId) {
        List<BomItem> items = bomItemMapper.selectByBomId(bomId);
        if (items.isEmpty()) {
            throw new BusinessException("BOM[id=" + bomId + "]没有明细，无法计算物料需求");
        }
        List<Long> materialIds = items.stream().map(BomItem::getMaterialId).toList();
        Map<Long, Material> materialMap = materialMapper.selectBatchIds(materialIds).stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));

        List<BomRequirementItem> result = new ArrayList<>(items.size());
        for (BomItem it : items) {
            Material m = materialMap.get(it.getMaterialId());
            if (m == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                        "BOM 明细引用的物料不存在: " + it.getMaterialId());
            }
            BomRequirementItem r = new BomRequirementItem();
            r.setMaterialId(m.getId());
            r.setMaterialCode(m.getMaterialCode());
            r.setMaterialName(m.getMaterialName());
            r.setSpec(m.getSpec());
            r.setUnit(m.getUnit());
            r.setQuantity(it.getQuantity());
            r.setLossRate(it.getLossRate());
            r.setIsBatchManaged(m.getIsBatchManaged());
            result.add(r);
        }
        return result;
    }

    /* ==================== BOM 树展开（多层 + 环路检测 + 深度限制） ==================== */

    /**
     * 展开产品的多层 BOM 树。
     *
     * <p>算法：DFS。用 {@code pathProductIds} 记录<b>当前递归路径</b>上的产品 ID，
     * 一旦发现重复即为环路 → 抛 {@link ErrorCode#BOM_CYCLE_DETECTED}。
     *
     * <p>为什么用"路径集合"而不是"全局已访问集合"：
     * 同一个半成品被两个不同父件引用是合法的（重复引用），
     * 只有出现在<b>同一条路径</b>上才是真环路。用全局集合会误报。
     *
     * <p>为什么要环路检测而不是靠深度限制兜底：
     * 深度限制只能防止栈溢出，但返回的数据依然是错的（A→B→A→B…），
     * 用户看到一棵诡异地重复的树却不知道错在哪。必须明确报错。
     */
    public List<BomTreeNode> getBomTree(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "产品不存在: " + productId);
        }
        Bom activeBom = bomMapper.selectActiveByProductId(productId);
        if (activeBom == null) {
            throw new BusinessException(ErrorCode.BOM_NOT_EFFECTIVE,
                    "产品[" + product.getProductCode() + "]未配置生效的 BOM");
        }
        Set<Long> path = new LinkedHashSet<>();
        path.add(productId);
        return expand(product, activeBom, BigDecimal.ONE, 1, path);
    }

    /**
     * 递归展开一个产品的 BOM 明细。
     *
     * @param product      当前产品（可能是半成品）
     * @param bom          当前产品生效的 BOM
     * @param parentFactor 从根到当前节点的累计用量乘积（用于折算总用量）
     * @param level        当前层级，根为 1
     * @param path         当前递归路径上的产品 ID（环路检测用）
     */
    private List<BomTreeNode> expand(Product product, Bom bom, BigDecimal parentFactor,
                                     int level, Set<Long> path) {
        if (level > CommonConstants.MAX_TREE_DEPTH) {
            throw new BusinessException("BOM 层级超过最大深度 "
                    + CommonConstants.MAX_TREE_DEPTH + "，请检查 BOM 配置");
        }

        List<BomItem> items = bomItemMapper.selectByBomId(bom.getId());
        if (items.isEmpty()) {
            return List.of();
        }
        List<Long> materialIds = items.stream().map(BomItem::getMaterialId).toList();
        Map<Long, Material> materialMap = materialMapper.selectBatchIds(materialIds).stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));

        // 一次性查出所有"产品编码 = 物料编码"的映射：用来识别"这个物料其实是半成品"
        // 本项目用产品编码前缀区分半成品（如 PRD-SEMI-*），但更严谨的做法是查 product 表。
        Map<String, Product> semiProductByCode = new HashMap<>();
        List<String> codes = materialMap.values().stream().map(Material::getMaterialCode).toList();
        if (!codes.isEmpty()) {
            productMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                            .in(Product::getProductCode, codes))
                    .forEach(p -> semiProductByCode.put(p.getProductCode(), p));
        }

        List<BomTreeNode> nodes = new ArrayList<>(items.size());
        for (BomItem it : items) {
            Material m = materialMap.get(it.getMaterialId());
            if (m == null) {
                continue;
            }
            BomTreeNode node = new BomTreeNode();
            node.setLevel(level);
            node.setCode(m.getMaterialCode());
            node.setName(m.getMaterialName());
            node.setSpec(m.getSpec());
            node.setUnit(m.getUnit());
            node.setQuantity(it.getQuantity());
            node.setLossRate(it.getLossRate());

            BigDecimal factor = it.getQuantity();
            BigDecimal total = parentFactor.multiply(factor);
            node.setTotalQuantity(total);

            // 该物料是否同时是"产品"（即半成品，可以继续展开）
            Product semi = semiProductByCode.get(m.getMaterialCode());
            if (semi == null) {
                node.setNodeType("MATERIAL");
                node.setMaterialId(m.getId());
                node.setHasChildren(false);
                node.setChildren(null);
                nodes.add(node);
                continue;
            }

            // 半成品：检查环路
            node.setNodeType("PRODUCT");
            node.setProductId(semi.getId());
            node.setMaterialId(m.getId());
            if (path.contains(semi.getId())) {
                throw new BusinessException(ErrorCode.BOM_CYCLE_DETECTED,
                        "BOM 存在循环引用：" + describePath(path) + " → " + semi.getProductCode());
            }

            Bom childBom = bomMapper.selectActiveByProductId(semi.getId());
            if (childBom == null) {
                // 半成品没配 BOM：当作叶子节点处理，但要标记出来让用户知道
                node.setHasChildren(false);
                node.setChildren(null);
                node.setSpec((m.getSpec() == null ? "" : m.getSpec()) + "（半成品未配置生效 BOM）");
                nodes.add(node);
                continue;
            }

            node.setHasChildren(true);
            Set<Long> childPath = new LinkedHashSet<>(path);
            childPath.add(semi.getId());
            node.setChildren(expand(semi, childBom, total, level + 1, childPath));
            nodes.add(node);
        }
        return nodes;
    }

    private String describePath(Set<Long> path) {
        return path.stream()
                .map(id -> {
                    Product p = productMapper.selectById(id);
                    return p == null ? String.valueOf(id) : p.getProductCode();
                })
                .collect(Collectors.joining(" → "));
    }
}
