package com.smart.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.result.PageResult;
import com.smart.product.dto.ProductDtos.MaterialSaveDTO;
import com.smart.product.dto.ProductDtos.ProductBrief;
import com.smart.product.dto.ProductDtos.ProductSaveDTO;
import com.smart.product.entity.Bom;
import com.smart.product.entity.BomItem;
import com.smart.product.entity.Material;
import com.smart.product.entity.Product;
import com.smart.product.entity.ProcessRoute;
import com.smart.product.mapper.BomItemMapper;
import com.smart.product.mapper.BomMapper;
import com.smart.product.mapper.MaterialMapper;
import com.smart.product.mapper.ProductMapper;
import com.smart.product.mapper.ProcessRouteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * 产品与物料服务（第 2 周任务 2.1 / 2.2）。
 *
 * <p>基础资料用<b>物理删除</b>，理由见 {@link com.smart.common.entity.BaseRefEntity}：
 * 逻辑删除后 {@code uk_product_code} 会阻止重建同编码，把 is_deleted 加进唯一索引
 * 又会导致"删除两次就重复"。基础资料被单据引用时存的是 id + 快照，物理删除不影响历史数据，
 * 只是删除前必须校验"是否已被 BOM / 工艺路线引用"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final MaterialMapper materialMapper;
    private final BomMapper bomMapper;
    private final BomItemMapper bomItemMapper;
    private final ProcessRouteMapper routeMapper;

    /* ==================== 产品 ==================== */

    public PageResult<Product> pageProduct(long current, long size, String keyword, Integer status) {
        Page<Product> page = new Page<>(current, size);
        LambdaQueryWrapper<Product> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            w.and(x -> x.like(Product::getProductCode, keyword)
                    .or().like(Product::getProductName, keyword));
        }
        if (status != null) {
            w.eq(Product::getStatus, status);
        }
        w.orderByDesc(Product::getId);
        Page<Product> r = productMapper.selectPage(page, w);
        return PageResult.of(r.getRecords(), r.getTotal(), current, size);
    }

    public Product getProduct(Long id) {
        Product p = productMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "产品不存在: " + id);
        }
        return p;
    }

    /**
     * 按编码查产品。用于 Feign 调用方按编码定位，以及生产服务建工单时校验。
     */
    public Product getProductByCode(String code) {
        Product p = productMapper.selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getProductCode, code));
        if (p == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "产品不存在: " + code);
        }
        return p;
    }

    @Transactional(rollbackFor = Exception.class)
    public Product createProduct(ProductSaveDTO dto) {
        Product p = new Product();
        applyProduct(p, dto);
        try {
            productMapper.insert(p);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：把数据库异常翻译成友好提示，不要抛 500
            throw new BusinessException(ErrorCode.DUPLICATE_KEY,
                    "产品编码已存在: " + dto.getProductCode());
        }
        log.info("[产品创建] id={} code={} name={}", p.getId(), p.getProductCode(), p.getProductName());
        return p;
    }

    @Transactional(rollbackFor = Exception.class)
    public Product updateProduct(Long id, ProductSaveDTO dto) {
        Product exist = getProduct(id);
        // 编码变更时单独校验唯一性（数据库唯一索引也会兜底）
        if (!exist.getProductCode().equals(dto.getProductCode())) {
            Long cnt = productMapper.selectCount(new LambdaQueryWrapper<Product>()
                    .eq(Product::getProductCode, dto.getProductCode()));
            if (cnt != null && cnt > 0) {
                throw new BusinessException(ErrorCode.DUPLICATE_KEY,
                        "产品编码已存在: " + dto.getProductCode());
            }
        }
        applyProduct(exist, dto);
        exist.setId(id);
        try {
            productMapper.updateById(exist);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_KEY,
                    "产品编码已存在: " + dto.getProductCode());
        }
        log.info("[产品更新] id={} code={}", id, exist.getProductCode());
        return exist;
    }

    /**
     * 删除产品。已被 BOM / 工艺路线引用时拒绝删除（物理删除的约束补偿）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteProduct(Long id) {
        getProduct(id);
        Long bomCount = bomMapper.selectCount(
                new LambdaQueryWrapper<Bom>().eq(Bom::getProductId, id));
        if (bomCount != null && bomCount > 0) {
            throw new BusinessException("产品已被 BOM 引用，无法删除（请先删除 BOM 版本）");
        }
        Long routeCount = routeMapper.selectCount(
                new LambdaQueryWrapper<ProcessRoute>().eq(ProcessRoute::getProductId, id));
        if (routeCount != null && routeCount > 0) {
            throw new BusinessException("产品已被工艺路线引用，无法删除（请先删除工艺路线版本）");
        }
        productMapper.deleteById(id);
        log.info("[产品删除] id={}", id);
    }

    private void applyProduct(Product p, ProductSaveDTO dto) {
        p.setProductCode(dto.getProductCode().trim());
        p.setProductName(dto.getProductName().trim());
        p.setSpec(dto.getSpec());
        p.setUnit(dto.getUnit());
        p.setCategory(dto.getCategory());
        p.setSafetyStock(dto.getSafetyStock() == null ? BigDecimal.ZERO : dto.getSafetyStock());
        p.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        p.setRemark(dto.getRemark());
    }

    /**
     * 转成跨服务轻量视图（Feign 返回体）。
     */
    public ProductBrief toBrief(Product p) {
        ProductBrief b = new ProductBrief();
        b.setId(p.getId());
        b.setProductCode(p.getProductCode());
        b.setProductName(p.getProductName());
        b.setSpec(p.getSpec());
        b.setUnit(p.getUnit());
        b.setCategory(p.getCategory());
        b.setStatus(p.getStatus());
        return b;
    }

    /* ==================== 物料 ==================== */

    public PageResult<Material> pageMaterial(long current, long size, String keyword,
                                             String materialType, Integer status) {
        Page<Material> page = new Page<>(current, size);
        LambdaQueryWrapper<Material> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            w.and(x -> x.like(Material::getMaterialCode, keyword)
                    .or().like(Material::getMaterialName, keyword));
        }
        if (StringUtils.hasText(materialType)) {
            w.eq(Material::getMaterialType, materialType);
        }
        if (status != null) {
            w.eq(Material::getStatus, status);
        }
        w.orderByAsc(Material::getMaterialCode);
        Page<Material> r = materialMapper.selectPage(page, w);
        return PageResult.of(r.getRecords(), r.getTotal(), current, size);
    }

    public Material getMaterial(Long id) {
        Material m = materialMapper.selectById(id);
        if (m == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "物料不存在: " + id);
        }
        return m;
    }

    public List<Material> listMaterials() {
        return materialMapper.selectList(new LambdaQueryWrapper<Material>()
                .orderByAsc(Material::getMaterialCode));
    }

    @Transactional(rollbackFor = Exception.class)
    public Material createMaterial(MaterialSaveDTO dto) {
        Material m = new Material();
        applyMaterial(m, dto);
        try {
            materialMapper.insert(m);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_KEY,
                    "物料编码已存在: " + dto.getMaterialCode());
        }
        log.info("[物料创建] id={} code={}", m.getId(), m.getMaterialCode());
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Material updateMaterial(Long id, MaterialSaveDTO dto) {
        Material exist = getMaterial(id);
        applyMaterial(exist, dto);
        exist.setId(id);
        try {
            materialMapper.updateById(exist);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_KEY,
                    "物料编码已存在: " + dto.getMaterialCode());
        }
        log.info("[物料更新] id={} code={}", id, exist.getMaterialCode());
        return exist;
    }

    /**
     * 删除物料。已被任何 BOM 明细引用时拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMaterial(Long id) {
        getMaterial(id);
        Long itemRef = bomItemMapper.selectCount(
                new LambdaQueryWrapper<BomItem>().eq(BomItem::getMaterialId, id));
        if (itemRef != null && itemRef > 0) {
            throw new BusinessException("物料已被 " + itemRef + " 条 BOM 明细引用，无法删除");
        }
        materialMapper.deleteById(id);
        log.info("[物料删除] id={}", id);
    }

    private void applyMaterial(Material m, MaterialSaveDTO dto) {
        m.setMaterialCode(dto.getMaterialCode().trim());
        m.setMaterialName(dto.getMaterialName().trim());
        m.setSpec(dto.getSpec());
        m.setUnit(dto.getUnit());
        m.setMaterialType(StringUtils.hasText(dto.getMaterialType()) ? dto.getMaterialType() : "RAW");
        m.setSafetyStock(dto.getSafetyStock() == null ? BigDecimal.ZERO : dto.getSafetyStock());
        m.setIsBatchManaged(dto.getIsBatchManaged() == null ? 1 : dto.getIsBatchManaged());
        m.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        m.setRemark(dto.getRemark());
    }
}
