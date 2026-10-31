package com.smart.quality.service;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.util.BizNoGenerator;
import com.smart.quality.dto.QualityDtos.InspectionCreateDTO;
import com.smart.quality.dto.QualityDtos.InspectionVO;
import com.smart.quality.dto.QualityDtos.RecordCreateDTO;
import com.smart.quality.dto.QualityDtos.RecordVO;
import com.smart.quality.entity.DefectRecord;
import com.smart.quality.entity.QualityInspection;
import com.smart.quality.entity.QualityRecord;
import com.smart.quality.mapper.DefectRecordMapper;
import com.smart.quality.mapper.QualityInspectionMapper;
import com.smart.quality.mapper.QualityRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 质检服务（第 6 周任务 6.3 / 6.4）。
 *
 * <h3>质检是工序流转的门禁</h3>
 * {@code is_inspection=1} 的工序报工完成后进入 {@code WAIT_INSPECTION}，
 * 必须质检合格（{@code result=1 合格} 或 {@code 3 让步接收}）才能放行到下一道。
 *
 * <h3>为什么 result 是四态</h3>
 * 合格 / 让步接收 / 返工 / 不合格 —— 实际生产中轻微不良可让步接收，
 * 部分不良可返工。二元状态无法表达这些业务分支。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionService {

    private final QualityInspectionMapper inspectionMapper;
    private final QualityRecordMapper recordMapper;
    private final DefectRecordMapper defectMapper;

    /**
     * 创建并录入质检单。
     */
    @Transactional(rollbackFor = Exception.class)
    public InspectionVO createInspection(InspectionCreateDTO dto) {
        // 数量校验：合格 + 不良 应等于检验数量
        BigDecimal sum = dto.getQualifiedQuantity().add(dto.getDefectQuantity());
        if (sum.compareTo(dto.getInspectionQuantity()) != 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    String.format("合格数(%s) + 不良数(%s) 必须等于检验数量(%s)",
                            dto.getQualifiedQuantity(), dto.getDefectQuantity(),
                            dto.getInspectionQuantity()));
        }

        QualityInspection insp = new QualityInspection();
        insp.setInspectionNo(BizNoGenerator.generateWithNano(BizNoGenerator.INSPECTION_PREFIX));
        insp.setInspectionType(dto.getInspectionType());
        insp.setOrderId(dto.getOrderId());
        insp.setOrderNo(dto.getOrderNo());
        insp.setTaskId(dto.getTaskId());
        insp.setStepNo(dto.getStepNo());
        insp.setStepName(dto.getStepName());
        insp.setBatchNo(dto.getBatchNo());
        insp.setInspectionQuantity(dto.getInspectionQuantity());
        insp.setQualifiedQuantity(dto.getQualifiedQuantity());
        insp.setDefectQuantity(dto.getDefectQuantity());
        insp.setResult(dto.getResult());
        insp.setStatus(2);
        insp.setInspectorId(dto.getInspectorId());
        insp.setInspectorName(dto.getInspectorName());
        insp.setInspectionTime(LocalDateTime.now());
        insp.setRemark(dto.getRemark());
        inspectionMapper.insert(insp);

        // 检验项明细
        if (dto.getRecords() != null) {
            for (RecordCreateDTO rd : dto.getRecords()) {
                QualityRecord qr = new QualityRecord();
                qr.setInspectionId(insp.getId());
                qr.setItemName(rd.getItemName());
                qr.setStandardValue(rd.getStandardValue());
                qr.setActualValue(rd.getActualValue());
                qr.setIsQualified(rd.getIsQualified() == null ? 1 : rd.getIsQualified());
                qr.setDefectCode(rd.getDefectCode());
                qr.setDefectName(rd.getDefectName());
                qr.setDefectQuantity(rd.getDefectQuantity() == null
                        ? BigDecimal.ZERO : rd.getDefectQuantity());
                qr.setRemark(rd.getRemark());
                recordMapper.insert(qr);
            }
        }

        // 不良品记录（质检来源）
        if (dto.getDefectQuantity() != null
                && dto.getDefectQuantity().compareTo(BigDecimal.ZERO) > 0) {
            DefectRecord dr = new DefectRecord();
            dr.setRecordNo(BizNoGenerator.generateWithNano("DF"));
            dr.setSourceType("INSPECTION");
            dr.setSourceId(insp.getId());
            dr.setOrderId(dto.getOrderId());
            dr.setOrderNo(dto.getOrderNo());
            dr.setTaskId(dto.getTaskId());
            dr.setStepName(dto.getStepName());
            dr.setBatchNo(dto.getBatchNo());
            dr.setDefectCode(dto.getRecords() != null && !dto.getRecords().isEmpty()
                    ? dto.getRecords().get(0).getDefectCode() : null);
            dr.setDefectQuantity(dto.getDefectQuantity());
            dr.setHandleType(handleTypeOf(dto.getResult()));
            dr.setHandleStatus(0);
            dr.setRemark("质检发现不良");
            defectMapper.insert(dr);
        }

        log.info("[质检单创建] no={} type={} result={} 合格={} 不良={}",
                insp.getInspectionNo(), QualityInspection.typeDesc(dto.getInspectionType()),
                QualityInspection.resultDesc(dto.getResult()),
                dto.getQualifiedQuantity(), dto.getDefectQuantity());
        return getInspection(insp.getId());
    }

    private Integer handleTypeOf(Integer result) {
        if (result == null) {
            return 4;
        }
        return switch (result) {
            case QualityInspection.RESULT_PASS -> 2;
            case QualityInspection.RESULT_CONCESSION -> 2;   // 让步接收
            case QualityInspection.RESULT_REWORK -> 1;       // 返工
            case QualityInspection.RESULT_FAIL -> 3;         // 报废
            default -> 4;
        };
    }

    public InspectionVO getInspection(Long id) {
        QualityInspection insp = inspectionMapper.selectById(id);
        if (insp == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "质检单不存在: " + id);
        }
        InspectionVO vo = new InspectionVO();
        vo.setId(insp.getId());
        vo.setInspectionNo(insp.getInspectionNo());
        vo.setInspectionType(insp.getInspectionType());
        vo.setInspectionTypeDesc(QualityInspection.typeDesc(insp.getInspectionType()));
        vo.setOrderId(insp.getOrderId());
        vo.setOrderNo(insp.getOrderNo());
        vo.setTaskId(insp.getTaskId());
        vo.setStepNo(insp.getStepNo());
        vo.setStepName(insp.getStepName());
        vo.setBatchNo(insp.getBatchNo());
        vo.setInspectionQuantity(insp.getInspectionQuantity());
        vo.setQualifiedQuantity(insp.getQualifiedQuantity());
        vo.setDefectQuantity(insp.getDefectQuantity());
        vo.setResult(insp.getResult());
        vo.setResultDesc(QualityInspection.resultDesc(insp.getResult()));
        vo.setStatus(insp.getStatus());
        vo.setInspectorName(insp.getInspectorName());
        vo.setInspectionTime(insp.getInspectionTime());
        vo.setRemark(insp.getRemark());

        List<RecordVO> records = new ArrayList<>();
        for (QualityRecord r : recordMapper.selectByInspectionId(id)) {
            RecordVO rv = new RecordVO();
            rv.setId(r.getId());
            rv.setItemName(r.getItemName());
            rv.setStandardValue(r.getStandardValue());
            rv.setActualValue(r.getActualValue());
            rv.setIsQualified(r.getIsQualified());
            rv.setDefectCode(r.getDefectCode());
            rv.setDefectName(r.getDefectName());
            rv.setDefectQuantity(r.getDefectQuantity());
            rv.setRemark(r.getRemark());
            records.add(rv);
        }
        vo.setRecords(records);
        return vo;
    }

    public List<InspectionVO> listByOrder(Long orderId) {
        return inspectionMapper.selectByOrderId(orderId).stream()
                .map(i -> getInspection(i.getId())).toList();
    }

    public List<InspectionVO> listByTask(Long taskId) {
        return inspectionMapper.selectByTaskId(taskId).stream()
                .map(i -> getInspection(i.getId())).toList();
    }

    /**
     * 工序质检是否已合格放行（供工序流转门禁校验）。
     */
    public boolean isTaskInspectionPassed(Long taskId) {
        QualityInspection last = inspectionMapper.selectLastFinishedByTaskId(taskId);
        if (last == null) {
            return false;
        }
        Integer r = last.getResult();
        // 合格 或 让步接收 → 放行
        return r != null && (r == QualityInspection.RESULT_PASS
                || r == QualityInspection.RESULT_CONCESSION);
    }

    /**
     * 不良代码统计（质量管理分析）。
     */
    public List<java.util.Map<String, Object>> defectStatistics() {
        return recordMapper.defectStatistics();
    }
}
