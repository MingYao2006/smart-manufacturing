package com.smart.quality.service;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.util.BizNoGenerator;
import com.smart.quality.dto.QualityDtos.BatchVO;
import com.smart.quality.dto.QualityDtos.MaterialBatchCreateDTO;
import com.smart.quality.dto.QualityDtos.ProductBatchCreateDTO;
import com.smart.quality.entity.ProductBatch;
import com.smart.quality.mapper.ProductBatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次服务（第 6 周任务 6.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchService {

    private final ProductBatchMapper batchMapper;
    private final TraceService traceService;

    /**
     * 创建物料批次（入库时生成，带供应商 —— 追溯链路的起点）。
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchVO createMaterialBatch(MaterialBatchCreateDTO dto) {
        ProductBatch b = new ProductBatch();
        b.setBatchNo(dto.getBatchNo() == null || dto.getBatchNo().isBlank()
                ? BizNoGenerator.generateWithNano(BizNoGenerator.MATERIAL_BATCH_PREFIX)
                : dto.getBatchNo());
        b.setBatchType(ProductBatch.TYPE_MATERIAL);
        b.setMaterialId(dto.getMaterialId());
        b.setMaterialCode(dto.getMaterialCode());
        b.setMaterialName(dto.getMaterialName());
        b.setQuantity(dto.getQuantity());
        b.setQualifiedQuantity(dto.getQuantity());
        b.setDefectQuantity(BigDecimal.ZERO);
        // 🔑 供应商是物料批次的追溯源头
        b.setSupplierId(dto.getSupplierId());
        b.setSupplierName(dto.getSupplierName());
        b.setInboundTime(LocalDateTime.now());
        b.setQualityStatus(dto.getQualityStatus() == null ? 1 : dto.getQualityStatus());
        b.setStatus(1);
        b.setRemark(dto.getRemark());
        try {
            batchMapper.insert(b);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_KEY, "批次号已存在: " + b.getBatchNo());
        }
        log.info("[物料批次创建] batchNo={} material={} qty={} supplier={}",
                b.getBatchNo(), b.getMaterialCode(), b.getQuantity(), b.getSupplierName());
        return traceService.toBatchVO(b);
    }

    /**
     * 创建产品批次（工单完工时生成）。
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchVO createProductBatch(ProductBatchCreateDTO dto) {
        ProductBatch b = new ProductBatch();
        b.setBatchNo(dto.getBatchNo() == null || dto.getBatchNo().isBlank()
                ? BizNoGenerator.generateWithNano(BizNoGenerator.PRODUCT_BATCH_PREFIX)
                : dto.getBatchNo());
        b.setBatchType(ProductBatch.TYPE_PRODUCT);
        b.setProductId(dto.getProductId());
        b.setProductCode(dto.getProductCode());
        b.setProductName(dto.getProductName());
        b.setProductionOrderId(dto.getProductionOrderId());
        b.setOrderNo(dto.getOrderNo());
        b.setQuantity(dto.getQuantity());
        b.setQualifiedQuantity(dto.getQualifiedQuantity() == null
                ? dto.getQuantity() : dto.getQualifiedQuantity());
        b.setDefectQuantity(dto.getDefectQuantity() == null
                ? BigDecimal.ZERO : dto.getDefectQuantity());
        b.setInboundTime(LocalDateTime.now());
        b.setQualityStatus(0);
        b.setStatus(1);
        b.setRemark(dto.getRemark());
        try {
            batchMapper.insert(b);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_KEY, "批次号已存在: " + b.getBatchNo());
        }
        log.info("[产品批次创建] batchNo={} product={} orderId={} qty={}",
                b.getBatchNo(), b.getProductCode(), dto.getProductionOrderId(), b.getQuantity());
        return traceService.toBatchVO(b);
    }

    public BatchVO getBatch(String batchNo) {
        return traceService.toBatchVO(traceService.requireBatch(batchNo));
    }

    public List<BatchVO> listByType(Integer batchType) {
        return batchMapper.selectByType(batchType).stream()
                .map(traceService::toBatchVO).toList();
    }

    public List<BatchVO> listByOrder(Long orderId) {
        return batchMapper.selectByOrderId(orderId).stream()
                .map(traceService::toBatchVO).toList();
    }

    public List<BatchVO> listBySupplier(Long supplierId) {
        return batchMapper.selectBySupplierId(supplierId).stream()
                .map(traceService::toBatchVO).toList();
    }
}
