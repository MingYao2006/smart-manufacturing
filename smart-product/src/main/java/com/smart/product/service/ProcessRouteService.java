package com.smart.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.product.dto.ProductDtos.RouteCreateDTO;
import com.smart.product.dto.ProductDtos.RouteVO;
import com.smart.product.dto.ProductDtos.StepDTO;
import com.smart.product.dto.ProductDtos.StepVO;
import com.smart.product.entity.ProcessRoute;
import com.smart.product.entity.ProcessStep;
import com.smart.product.entity.Product;
import com.smart.product.enums.VersionStatus;
import com.smart.product.mapper.ProcessRouteMapper;
import com.smart.product.mapper.ProcessStepMapper;
import com.smart.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工艺路线服务（第 2 周任务 2.5）。
 *
 * <p>与 BOM 同构的版本化对象：草稿 → 生效（顶替旧版本）→ 失效。
 *
 * <p>关键点：
 * <ul>
 *   <li>{@code stepNo} 必须唯一且有序（用 10/20/30 留插空位）</li>
 *   <li>{@code isInspection=1} 的工序是质检门禁，完成后必须质检合格才能流转</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessRouteService {

    private final ProcessRouteMapper routeMapper;
    private final ProcessStepMapper stepMapper;
    private final ProductMapper productMapper;

    /**
     * 查产品当前生效的工艺路线。没有则抛 {@link ErrorCode#ROUTE_NOT_EFFECTIVE}。
     */
    public ProcessRoute requireActiveRoute(Long productId) {
        ProcessRoute r = routeMapper.selectActiveByProductId(productId);
        if (r == null) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_EFFECTIVE,
                    "产品[id=" + productId + "]未配置生效的工艺路线，请先创建并审核生效");
        }
        return r;
    }

    public List<ProcessRoute> listByProduct(Long productId) {
        return routeMapper.selectByProductId(productId);
    }

    /**
     * 查有序工序列表 —— 生产服务下达工单时用它生成 work_task。
     */
    public List<ProcessStep> listSteps(Long routeId) {
        return stepMapper.selectByRouteId(routeId);
    }

    public RouteVO getRouteDetail(Long routeId) {
        ProcessRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工艺路线不存在: " + routeId);
        }
        return toVO(route);
    }

    public RouteVO getActiveRouteByProduct(Long productId) {
        return toVO(requireActiveRoute(productId));
    }

    private RouteVO toVO(ProcessRoute route) {
        RouteVO vo = new RouteVO();
        vo.setId(route.getId());
        vo.setProductId(route.getProductId());
        vo.setRouteVersion(route.getRouteVersion());
        vo.setStatus(route.getStatus());
        vo.setStatusDesc(VersionStatus.descOf(route.getStatus()));
        vo.setEffectiveDate(route.getEffectiveDate());
        vo.setRemark(route.getRemark());

        Product p = productMapper.selectById(route.getProductId());
        if (p != null) {
            vo.setProductCode(p.getProductCode());
            vo.setProductName(p.getProductName());
        }

        List<StepVO> steps = new ArrayList<>();
        for (ProcessStep s : stepMapper.selectByRouteId(route.getId())) {
            StepVO sv = new StepVO();
            sv.setId(s.getId());
            sv.setStepNo(s.getStepNo());
            sv.setStepName(s.getStepName());
            sv.setWorkCenter(s.getWorkCenter());
            sv.setStandardHours(s.getStandardHours());
            sv.setIsInspection(s.getIsInspection());
            sv.setDescription(s.getDescription());
            steps.add(sv);
        }
        vo.setSteps(steps);
        return vo;
    }

    /**
     * 创建工艺路线（草稿）。
     */
    @Transactional(rollbackFor = Exception.class)
    public RouteVO createRoute(RouteCreateDTO dto) {
        Product product = productMapper.selectById(dto.getProductId());
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "产品不存在: " + dto.getProductId());
        }

        // 工序号必须唯一
        Set<Integer> stepNos = new HashSet<>();
        for (StepDTO s : dto.getSteps()) {
            if (!stepNos.add(s.getStepNo())) {
                throw new BusinessException("工序号重复: " + s.getStepNo());
            }
        }

        String version = dto.getRouteVersion();
        if (version == null || version.isBlank()) {
            version = "V" + (routeMapper.countReleasedVersions(dto.getProductId()) + 1);
        }

        ProcessRoute route = new ProcessRoute();
        route.setProductId(dto.getProductId());
        route.setRouteVersion(version);
        route.setStatus(VersionStatus.DRAFT.getCode());
        route.setRemark(dto.getRemark());
        try {
            routeMapper.insert(route);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_KEY,
                    "该产品已存在版本 " + version + " 的工艺路线");
        }

        // 按工序号排序后插入，保证展示顺序稳定
        List<StepDTO> sorted = dto.getSteps().stream()
                .sorted((a, b) -> Integer.compare(a.getStepNo(), b.getStepNo()))
                .toList();
        for (StepDTO s : sorted) {
            ProcessStep step = new ProcessStep();
            step.setRouteId(route.getId());
            step.setStepNo(s.getStepNo());
            step.setStepName(s.getStepName());
            step.setWorkCenter(s.getWorkCenter());
            step.setStandardHours(s.getStandardHours() == null ? BigDecimal.ZERO : s.getStandardHours());
            step.setIsInspection(s.getIsInspection() == null ? 0 : s.getIsInspection());
            step.setDescription(s.getDescription());
            stepMapper.insert(step);
        }
        log.info("[工艺路线创建] id={} product={} version={} steps={}",
                route.getId(), product.getProductCode(), version, dto.getSteps().size());
        return getRouteDetail(route.getId());
    }

    /**
     * 审核生效：草稿 → 生效，顶替同产品其他生效版本（同一事务）。
     */
    @Transactional(rollbackFor = Exception.class)
    public RouteVO activateRoute(Long routeId, LocalDate effectiveDate) {
        ProcessRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工艺路线不存在: " + routeId);
        }
        if (route.getStatus() == null || route.getStatus() != VersionStatus.DRAFT.getCode()) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "只有草稿状态的工艺路线可以生效，当前状态：" + VersionStatus.descOf(route.getStatus()));
        }
        if (stepMapper.countByRouteId(routeId) == 0) {
            throw new BusinessException("工艺路线没有工序，无法生效");
        }
        long affected = routeMapper.activateCas(routeId, effectiveDate);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT,
                    "工艺路线状态已被其他操作变更，请刷新后重试");
        }
        long deactivated = routeMapper.deactivateOthers(route.getProductId(), routeId);
        log.info("[工艺路线生效] id={} version={} 顶替失效的旧版本数={}",
                routeId, route.getRouteVersion(), deactivated);
        return getRouteDetail(routeId);
    }

    /**
     * 删除工艺路线（仅草稿可删）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRoute(Long routeId) {
        ProcessRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工艺路线不存在: " + routeId);
        }
        if (route.getStatus() == null || route.getStatus() != VersionStatus.DRAFT.getCode()) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "只有草稿状态的工艺路线可以删除");
        }
        stepMapper.delete(new LambdaQueryWrapper<ProcessStep>().eq(ProcessStep::getRouteId, routeId));
        routeMapper.deleteById(routeId);
        log.info("[工艺路线删除] id={}", routeId);
    }
}
