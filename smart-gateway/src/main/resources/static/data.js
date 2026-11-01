(function () {
  const now = new Date();
  const pad = (value) => String(value).padStart(2, '0');
  const dateKey = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`;
  const at = (hours, minutes) =>
    new Date(now.getFullYear(), now.getMonth(), now.getDate(), hours, minutes, 0).toISOString();
  const inMinutes = (minutes) => new Date(now.getTime() + minutes * 60000).toISOString();
  const agoMinutes = (minutes) => new Date(now.getTime() - minutes * 60000).toISOString();

  const orderStatus = {
    1: { label: '草稿', tone: 'neutral' },
    2: { label: '已审核', tone: 'steel' },
    3: { label: '已下达', tone: 'blue' },
    4: { label: '生产中', tone: 'active' },
    5: { label: '暂停', tone: 'warning' },
    6: { label: '已完工', tone: 'success' },
    7: { label: '已关闭', tone: 'neutral' },
    8: { label: '已取消', tone: 'neutral' }
  };

  const taskStatus = {
    1: { label: '待开始', tone: 'neutral' },
    2: { label: '进行中', tone: 'active' },
    3: { label: '暂停', tone: 'warning' },
    4: { label: '已完成', tone: 'success' },
    5: { label: '已跳过', tone: 'neutral' },
    6: { label: '待质检', tone: 'caution' }
  };

  const orders = [
    {
      id: 1,
      orderNo: `MO-${dateKey}-004`,
      planId: 1,
      productId: 1,
      productCode: 'PRD-DB-X1',
      productName: '智能电表 X1',
      spec: '220V/5(60)A',
      unit: '台',
      bomVersion: 'V1',
      routeVersion: 'V1',
      planQuantity: 320,
      completedQuantity: 172,
      qualifiedQuantity: 168,
      defectQuantity: 4,
      progress: 54,
      status: 4,
      statusDesc: '生产中',
      planStartTime: at(8, 0),
      planEndTime: at(16, 0),
      actualStartTime: at(8, 12),
      remark: '华东电网补产批次，交期锁定 16:00'
    },
    {
      id: 2,
      orderNo: `MO-${dateKey}-005`,
      planId: 2,
      productId: 2,
      productCode: 'PRD-DB-X2',
      productName: '智能电表 X2',
      spec: '220V/10(100)A',
      unit: '台',
      bomVersion: 'V1',
      routeVersion: 'V1',
      planQuantity: 200,
      completedQuantity: 120,
      qualifiedQuantity: 118,
      defectQuantity: 2,
      progress: 60,
      status: 4,
      statusDesc: '生产中',
      planStartTime: at(8, 30),
      planEndTime: at(17, 30),
      actualStartTime: at(8, 44),
      remark: '大电流型号，老化房 2 号架'
    },
    {
      id: 3,
      orderNo: `MO-${dateKey}-003`,
      planId: 3,
      productId: 1,
      productCode: 'PRD-DB-X1',
      productName: '智能电表 X1',
      spec: '220V/5(60)A',
      unit: '台',
      bomVersion: 'V1',
      routeVersion: 'V1',
      planQuantity: 480,
      completedQuantity: 418,
      qualifiedQuantity: 411,
      defectQuantity: 7,
      progress: 87,
      status: 5,
      statusDesc: '暂停',
      planStartTime: at(7, 40),
      planEndTime: at(15, 55),
      actualStartTime: at(7, 52),
      remark: '包装线等待 LCD 补料，前道已完成'
    },
    {
      id: 4,
      orderNo: `MO-${dateKey}-006`,
      planId: 4,
      productId: 3,
      productCode: 'PRD-SEMI-C1',
      productName: '电表控制板 C1',
      spec: '含 MCU + 计量芯片',
      unit: '块',
      bomVersion: 'V1',
      routeVersion: 'V1',
      planQuantity: 600,
      completedQuantity: 0,
      qualifiedQuantity: 0,
      defectQuantity: 0,
      progress: 0,
      status: 3,
      statusDesc: '已下达',
      planStartTime: inMinutes(110),
      planEndTime: inMinutes(1290),
      actualStartTime: null,
      remark: '为 X1/X2 总装预留，晚班前完成首批'
    },
    {
      id: 5,
      orderNo: `MO-${dateKey}-002`,
      planId: 5,
      productId: 1,
      productCode: 'PRD-DB-X1',
      productName: '智能电表 X1',
      spec: '220V/5(60)A',
      unit: '台',
      bomVersion: 'V1',
      routeVersion: 'V1',
      planQuantity: 200,
      completedQuantity: 200,
      qualifiedQuantity: 197,
      defectQuantity: 3,
      progress: 100,
      status: 6,
      statusDesc: '已完工',
      planStartTime: at(7, 30),
      planEndTime: at(12, 30),
      actualStartTime: at(7, 38),
      actualEndTime: at(12, 12),
      remark: '等待质量放行后关闭'
    },
    {
      id: 6,
      orderNo: `MO-${dateKey}-007`,
      planId: 6,
      productId: 2,
      productCode: 'PRD-DB-X2',
      productName: '智能电表 X2',
      spec: '220V/10(100)A',
      unit: '台',
      bomVersion: 'V1',
      routeVersion: 'V1',
      planQuantity: 160,
      completedQuantity: 0,
      qualifiedQuantity: 0,
      defectQuantity: 0,
      progress: 0,
      status: 2,
      statusDesc: '已审核',
      planStartTime: inMinutes(300),
      planEndTime: inMinutes(960),
      actualStartTime: null,
      remark: '晚班计划，等待下达'
    }
  ];

  const tasksByOrder = {
    1: [
      {
        id: 101,
        orderId: 1,
        orderNo: orders[0].orderNo,
        stepNo: 10,
        stepName: '外壳组装',
        workCenter: '装配一线',
        standardHours: 0.25,
        planQuantity: 320,
        reportedQuantity: 320,
        qualifiedQuantity: 316,
        defectQuantity: 4,
        actualHours: 1.8,
        status: 4,
        statusDesc: '已完成',
        isInspection: 0,
        operatorId: 4,
        operatorName: '王工',
        startTime: at(8, 12),
        endTime: at(9, 58)
      },
      {
        id: 102,
        orderId: 1,
        orderNo: orders[0].orderNo,
        stepNo: 20,
        stepName: '老化测试',
        workCenter: '老化房',
        standardHours: 0.5,
        planQuantity: 320,
        reportedQuantity: 172,
        qualifiedQuantity: 168,
        defectQuantity: 4,
        actualHours: 1.37,
        status: 2,
        statusDesc: '进行中',
        isInspection: 1,
        operatorId: 4,
        operatorName: '王工',
        startTime: agoMinutes(82),
        endTime: null
      },
      {
        id: 103,
        orderId: 1,
        orderNo: orders[0].orderNo,
        stepNo: 30,
        stepName: '包装入库',
        workCenter: '包装线',
        standardHours: 0.15,
        planQuantity: 320,
        reportedQuantity: 0,
        qualifiedQuantity: 0,
        defectQuantity: 0,
        actualHours: 0,
        status: 1,
        statusDesc: '待开始',
        isInspection: 0,
        operatorId: null,
        operatorName: '待派工',
        startTime: null,
        endTime: null
      }
    ],
    2: [
      {
        id: 201,
        orderId: 2,
        orderNo: orders[1].orderNo,
        stepNo: 10,
        stepName: '外壳组装',
        workCenter: '装配二线',
        standardHours: 0.25,
        planQuantity: 200,
        reportedQuantity: 200,
        qualifiedQuantity: 198,
        defectQuantity: 2,
        actualHours: 1.2,
        status: 4,
        statusDesc: '已完成',
        isInspection: 0,
        operatorId: 7,
        operatorName: '陈工',
        startTime: at(8, 44),
        endTime: at(10, 4)
      },
      {
        id: 202,
        orderId: 2,
        orderNo: orders[1].orderNo,
        stepNo: 20,
        stepName: '老化测试',
        workCenter: '老化房',
        standardHours: 0.5,
        planQuantity: 200,
        reportedQuantity: 120,
        qualifiedQuantity: 118,
        defectQuantity: 2,
        actualHours: 2.05,
        status: 6,
        statusDesc: '待质检',
        isInspection: 1,
        operatorId: 7,
        operatorName: '陈工',
        startTime: agoMinutes(132),
        endTime: agoMinutes(14)
      },
      {
        id: 203,
        orderId: 2,
        orderNo: orders[1].orderNo,
        stepNo: 30,
        stepName: '包装入库',
        workCenter: '包装线',
        standardHours: 0.15,
        planQuantity: 200,
        reportedQuantity: 0,
        qualifiedQuantity: 0,
        defectQuantity: 0,
        actualHours: 0,
        status: 1,
        statusDesc: '待开始',
        isInspection: 0,
        operatorId: null,
        operatorName: '待派工',
        startTime: null,
        endTime: null
      }
    ],
    3: [
      {
        id: 301,
        orderId: 3,
        orderNo: orders[2].orderNo,
        stepNo: 10,
        stepName: '外壳组装',
        workCenter: '装配一线',
        standardHours: 0.25,
        planQuantity: 480,
        reportedQuantity: 418,
        qualifiedQuantity: 411,
        defectQuantity: 7,
        actualHours: 3.1,
        status: 4,
        statusDesc: '已完成',
        isInspection: 0,
        operatorId: 8,
        operatorName: '刘工',
        startTime: at(7, 52),
        endTime: agoMinutes(61)
      },
      {
        id: 302,
        orderId: 3,
        orderNo: orders[2].orderNo,
        stepNo: 20,
        stepName: '包装入库',
        workCenter: '包装线',
        standardHours: 0.15,
        planQuantity: 480,
        reportedQuantity: 0,
        qualifiedQuantity: 0,
        defectQuantity: 0,
        actualHours: 0.6,
        status: 3,
        statusDesc: '暂停',
        isInspection: 0,
        operatorId: 8,
        operatorName: '刘工',
        startTime: agoMinutes(54),
        endTime: null
      }
    ],
    4: [
      {
        id: 401,
        orderId: 4,
        orderNo: orders[3].orderNo,
        stepNo: 10,
        stepName: 'SMT 贴片',
        workCenter: 'SMT 车间',
        standardHours: 0.08,
        planQuantity: 600,
        reportedQuantity: 0,
        qualifiedQuantity: 0,
        defectQuantity: 0,
        actualHours: 0,
        status: 1,
        statusDesc: '待开始',
        isInspection: 0,
        operatorId: null,
        operatorName: '待派工',
        startTime: null,
        endTime: null
      },
      {
        id: 402,
        orderId: 4,
        orderNo: orders[3].orderNo,
        stepNo: 20,
        stepName: 'ICT 测试',
        workCenter: '测试台',
        standardHours: 0.12,
        planQuantity: 600,
        reportedQuantity: 0,
        qualifiedQuantity: 0,
        defectQuantity: 0,
        actualHours: 0,
        status: 1,
        statusDesc: '待开始',
        isInspection: 1,
        operatorId: null,
        operatorName: '待派工',
        startTime: null,
        endTime: null
      }
    ],
    5: [
      {
        id: 501,
        orderId: 5,
        orderNo: orders[4].orderNo,
        stepNo: 10,
        stepName: '外壳组装',
        workCenter: '装配一线',
        standardHours: 0.25,
        planQuantity: 200,
        reportedQuantity: 200,
        qualifiedQuantity: 197,
        defectQuantity: 3,
        actualHours: 1.4,
        status: 4,
        statusDesc: '已完成',
        isInspection: 0,
        operatorId: 4,
        operatorName: '王工',
        startTime: at(7, 38),
        endTime: at(9, 6)
      },
      {
        id: 502,
        orderId: 5,
        orderNo: orders[4].orderNo,
        stepNo: 20,
        stepName: '老化测试',
        workCenter: '老化房',
        standardHours: 0.5,
        planQuantity: 200,
        reportedQuantity: 200,
        qualifiedQuantity: 197,
        defectQuantity: 3,
        actualHours: 2.1,
        status: 4,
        statusDesc: '已完成',
        isInspection: 1,
        operatorId: 4,
        operatorName: '王工',
        startTime: at(9, 10),
        endTime: at(11, 22)
      },
      {
        id: 503,
        orderId: 5,
        orderNo: orders[4].orderNo,
        stepNo: 30,
        stepName: '包装入库',
        workCenter: '包装线',
        standardHours: 0.15,
        planQuantity: 200,
        reportedQuantity: 200,
        qualifiedQuantity: 197,
        defectQuantity: 3,
        actualHours: 0.7,
        status: 4,
        statusDesc: '已完成',
        isInspection: 0,
        operatorId: 4,
        operatorName: '王工',
        startTime: at(11, 25),
        endTime: at(12, 0)
      }
    ],
    6: [
      {
        id: 601,
        orderId: 6,
        orderNo: orders[5].orderNo,
        stepNo: 10,
        stepName: '外壳组装',
        workCenter: '装配二线',
        standardHours: 0.25,
        planQuantity: 160,
        reportedQuantity: 0,
        qualifiedQuantity: 0,
        defectQuantity: 0,
        actualHours: 0,
        status: 1,
        statusDesc: '待开始',
        isInspection: 0,
        operatorId: null,
        operatorName: '待派工',
        startTime: null,
        endTime: null
      },
      {
        id: 602,
        orderId: 6,
        orderNo: orders[5].orderNo,
        stepNo: 20,
        stepName: '老化测试',
        workCenter: '老化房',
        standardHours: 0.5,
        planQuantity: 160,
        reportedQuantity: 0,
        qualifiedQuantity: 0,
        defectQuantity: 0,
        actualHours: 0,
        status: 1,
        statusDesc: '待开始',
        isInspection: 1,
        operatorId: null,
        operatorName: '待派工',
        startTime: null,
        endTime: null
      },
      {
        id: 603,
        orderId: 6,
        orderNo: orders[5].orderNo,
        stepNo: 30,
        stepName: '包装入库',
        workCenter: '包装线',
        standardHours: 0.15,
        planQuantity: 160,
        reportedQuantity: 0,
        qualifiedQuantity: 0,
        defectQuantity: 0,
        actualHours: 0,
        status: 1,
        statusDesc: '待开始',
        isInspection: 0,
        operatorId: null,
        operatorName: '待派工',
        startTime: null,
        endTime: null
      }
    ]
  };

  const inventory = [
    {
      materialId: 1,
      materialCode: 'MAT-MCU-01',
      materialName: '计量芯片 MCU-A',
      unit: '颗',
      quantity: 684,
      reservedQuantity: 500,
      availableQuantity: 184,
      safetyStock: 500,
      belowSafetyStock: true,
      batchNo: 'MB20250101001',
      supplierName: '深圳华芯电子有限公司'
    },
    {
      materialId: 2,
      materialCode: 'MAT-SHELL-01',
      materialName: '表壳 ABS 外壳',
      unit: '个',
      quantity: 324,
      reservedQuantity: 200,
      availableQuantity: 124,
      safetyStock: 200,
      belowSafetyStock: true,
      batchNo: 'MB20250101002',
      supplierName: '东莞精塑制品厂'
    },
    {
      materialId: 3,
      materialCode: 'MAT-SCREW-M3',
      materialName: 'M3 自攻螺丝',
      unit: '颗',
      quantity: 8400,
      reservedQuantity: 6000,
      availableQuantity: 2400,
      safetyStock: 2000,
      belowSafetyStock: false,
      batchNo: 'MB20250101003',
      supplierName: '苏州标准件有限公司'
    },
    {
      materialId: 4,
      materialCode: 'MAT-LCD-01',
      materialName: 'LCD 液晶屏',
      unit: '块',
      quantity: 336,
      reservedQuantity: 300,
      availableQuantity: 36,
      safetyStock: 300,
      belowSafetyStock: true,
      shortage: 60,
      batchNo: 'MB20250101004',
      supplierName: '京东方科技集团'
    },
    {
      materialId: 5,
      materialCode: 'MAT-PCB-01',
      materialName: 'PCB 电路板',
      unit: '块',
      quantity: 628,
      reservedQuantity: 500,
      availableQuantity: 128,
      safetyStock: 300,
      belowSafetyStock: true,
      batchNo: 'MB20250101006',
      supplierName: '深圳景旺电子'
    },
    {
      materialId: 6,
      materialCode: 'MAT-CAP-01',
      materialName: '电解电容 100uF',
      unit: '颗',
      quantity: 3920,
      reservedQuantity: 3000,
      availableQuantity: 920,
      safetyStock: 1000,
      belowSafetyStock: true,
      batchNo: 'MB20250101007',
      supplierName: '东莞电容器厂'
    },
    {
      materialId: 7,
      materialCode: 'MAT-RES-01',
      materialName: '贴片电阻 10K',
      unit: '颗',
      quantity: 12100,
      reservedQuantity: 8000,
      availableQuantity: 4100,
      safetyStock: 5000,
      belowSafetyStock: true,
      batchNo: 'MB20250101008',
      supplierName: '风华高科'
    },
    {
      materialId: 8,
      materialCode: 'MAT-WIRE-01',
      materialName: '铜芯导线',
      unit: '米',
      quantity: 1010,
      reservedQuantity: 800,
      availableQuantity: 210,
      safetyStock: 500,
      belowSafetyStock: true,
      shortage: 24,
      batchNo: 'MB20250101005',
      supplierName: '江苏电缆有限公司'
    },
    {
      materialId: 9,
      materialCode: 'MAT-BOX-01',
      materialName: '彩盒包装',
      unit: '个',
      quantity: 556,
      reservedQuantity: 400,
      availableQuantity: 156,
      safetyStock: 200,
      belowSafetyStock: true,
      batchNo: 'MB20250101009',
      supplierName: '宁波彩印包装厂'
    },
    {
      materialId: 10,
      materialCode: 'MAT-SEAL-01',
      materialName: '铅封',
      unit: '个',
      quantity: 1230,
      reservedQuantity: 800,
      availableQuantity: 430,
      safetyStock: 500,
      belowSafetyStock: true,
      batchNo: 'MB20250101010',
      supplierName: '上海封条制造厂'
    }
  ];

  const trace = {
    direction: 'BACKWARD',
    root: {
      batchNo: `PB${dateKey}004`,
      batchType: 2,
      batchTypeDesc: '产品批次',
      code: 'PRD-DB-X1',
      name: '智能电表 X1',
      quantity: 172,
      qualityStatus: 1,
      productionOrderId: 1,
      orderNo: orders[0].orderNo,
      inboundTime: agoMinutes(36)
    },
    totalNodes: 10,
    maxDepth: 3,
    truncated: false,
    message: '追溯链路完整，全部 10 个批次关联到供应商',
    tree: [
      {
        level: 1,
        batchNo: 'MB20250101001',
        batchType: 1,
        batchTypeDesc: '物料批次',
        code: 'MAT-MCU-01',
        name: '计量芯片 MCU-A',
        batchQuantity: 1000,
        edgeQuantity: 172,
        supplierId: 1001,
        supplierName: '深圳华芯电子有限公司',
        hasChildren: false,
        children: []
      },
      {
        level: 1,
        batchNo: 'MB20250101002',
        batchType: 1,
        batchTypeDesc: '物料批次',
        code: 'MAT-SHELL-01',
        name: '表壳 ABS 外壳',
        batchQuantity: 500,
        edgeQuantity: 172,
        supplierId: 1002,
        supplierName: '东莞精塑制品厂',
        hasChildren: false,
        children: []
      },
      {
        level: 1,
        batchNo: 'MB20250101004',
        batchType: 1,
        batchTypeDesc: '物料批次',
        code: 'MAT-LCD-01',
        name: 'LCD 液晶屏',
        batchQuantity: 50,
        edgeQuantity: 172,
        supplierId: 1004,
        supplierName: '京东方科技集团',
        hasChildren: false,
        children: []
      },
      {
        level: 1,
        batchNo: 'MB20250101005',
        batchType: 1,
        batchTypeDesc: '物料批次',
        code: 'MAT-WIRE-01',
        name: '铜芯导线',
        batchQuantity: 800,
        edgeQuantity: 344,
        supplierId: 1005,
        supplierName: '江苏电缆有限公司',
        hasChildren: false,
        children: []
      }
    ]
  };

  const qualityQueue = [
    {
      id: 9001,
      inspectionNo: 'QI-20261031-012',
      inspectionType: 5,
      inspectionTypeDesc: '工序检',
      orderId: 2,
      orderNo: orders[1].orderNo,
      taskId: 202,
      stepNo: 20,
      stepName: '老化测试',
      inspectionQuantity: 120,
      qualifiedQuantity: 118,
      defectQuantity: 2,
      result: null,
      resultDesc: '待判定',
      status: 'pending',
      inspectorName: '赵质检',
      inspectionTime: agoMinutes(12),
      note: '2 台通讯超时，等待返工判定'
    },
    {
      id: 9002,
      inspectionNo: 'QI-20261031-011',
      inspectionType: 4,
      inspectionTypeDesc: '完工检',
      orderId: 5,
      orderNo: orders[4].orderNo,
      taskId: 503,
      stepNo: 30,
      stepName: '包装入库',
      inspectionQuantity: 200,
      qualifiedQuantity: 197,
      defectQuantity: 3,
      result: 1,
      resultDesc: '合格',
      status: 'passed',
      inspectorName: '赵质检',
      inspectionTime: agoMinutes(48),
      note: '外观项 3 台让步接收'
    },
    {
      id: 9003,
      inspectionNo: 'QI-20261030-038',
      inspectionType: 1,
      inspectionTypeDesc: '来料检',
      orderId: null,
      orderNo: 'MB20250101004',
      taskId: null,
      stepNo: null,
      stepName: 'LCD 液晶屏',
      inspectionQuantity: 50,
      qualifiedQuantity: 49,
      defectQuantity: 1,
      result: 1,
      resultDesc: '合格',
      status: 'passed',
      inspectorName: '孙质检',
      inspectionTime: agoMinutes(920),
      note: '1 块点亮异常已隔离'
    }
  ];

  const statusEvents = {
    1: [
      { fromStatus: 3, fromStatusDesc: '已下达', toStatus: 4, toStatusDesc: '生产中', reason: '首道工序开始', operatorName: '王工', operateTime: at(8, 12) },
      { fromStatus: 2, fromStatusDesc: '已审核', toStatus: 3, toStatusDesc: '已下达', reason: '计划确认，生成 3 道工序', operatorName: '张计划', operateTime: at(7, 48) },
      { fromStatus: 1, fromStatusDesc: '草稿', toStatus: 2, toStatusDesc: '已审核', reason: 'BOM 与工艺路线校验通过', operatorName: '张计划', operateTime: at(7, 32) }
    ],
    2: [
      { fromStatus: 3, fromStatusDesc: '已下达', toStatus: 4, toStatusDesc: '生产中', reason: '首道工序开始', operatorName: '陈工', operateTime: at(8, 44) },
      { fromStatus: 2, fromStatusDesc: '已审核', toStatus: 3, toStatusDesc: '已下达', reason: '计划确认，生成 3 道工序', operatorName: '张计划', operateTime: at(8, 2) }
    ],
    3: [
      { fromStatus: 4, fromStatusDesc: '生产中', toStatus: 5, toStatusDesc: '暂停', reason: 'LCD 欠料，包装线暂停', operatorName: '刘工', operateTime: agoMinutes(54) },
      { fromStatus: 3, fromStatusDesc: '已下达', toStatus: 4, toStatusDesc: '生产中', reason: '首道工序开始', operatorName: '刘工', operateTime: at(7, 52) }
    ],
    4: [
      { fromStatus: 2, fromStatusDesc: '已审核', toStatus: 3, toStatusDesc: '已下达', reason: '生成 SMT 与 ICT 工序', operatorName: '张计划', operateTime: agoMinutes(96) }
    ],
    5: [
      { fromStatus: 4, fromStatusDesc: '生产中', toStatus: 6, toStatusDesc: '已完工', reason: '全部工序完成，质量放行', operatorName: '系统', operateTime: at(12, 12) }
    ],
    6: [
      { fromStatus: 1, fromStatusDesc: '草稿', toStatus: 2, toStatusDesc: '已审核', reason: '晚班计划审核', operatorName: '张计划', operateTime: agoMinutes(40) }
    ]
  };

  /* 历史完工工单（archived）
     目的：让工作台的「生产趋势」在演示模式下有近 7 天的真实分布，而不是 7 天里
     只有 1 天有值、整张图几乎空白。
     archived=true 的含义：不计入「今日计划」等 KPI，也不占用工单队列名额。
     这些记录与其余演示数据一样是合成数据，只用于演示界面，不代表真实产量。 */
  const dayKey = (offset) => {
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() - offset);
    return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}`;
  };
  const dayAt = (offset, hours, minutes) =>
    new Date(now.getFullYear(), now.getMonth(), now.getDate() - offset, hours, minutes, 0).toISOString();

  const historyProducts = {
    1: { productId: 1, productCode: 'PRD-DB-X1', productName: '智能电表 X1', spec: '220V/5(60)A', unit: '台' },
    2: { productId: 2, productCode: 'PRD-DB-X2', productName: '智能电表 X2', spec: '220V/10(100)A', unit: '台' },
    3: { productId: 3, productCode: 'PRD-SEMI-C1', productName: '电表控制板 C1', spec: '含 MCU + 计量芯片', unit: '块' }
  };

  const historyPlans = [
    { offset: 1, seq: '001', productId: 1, plan: 240, defect: 3 },
    { offset: 2, seq: '002', productId: 2, plan: 180, defect: 2 },
    { offset: 3, seq: '003', productId: 1, plan: 300, defect: 5 },
    { offset: 4, seq: '004', productId: 3, plan: 420, defect: 4 },
    { offset: 5, seq: '005', productId: 2, plan: 200, defect: 1 },
    { offset: 6, seq: '006', productId: 1, plan: 260, defect: 2 }
  ];

  historyPlans.forEach((item, index) => {
    const meta = historyProducts[item.productId];
    const orderId = 7 + index;
    const orderNo = `MO-${dayKey(item.offset)}-${item.seq}`;
    const qualified = item.plan - item.defect;
    orders.push({
      id: orderId,
      orderNo,
      planId: 10 + index,
      productId: meta.productId,
      productCode: meta.productCode,
      productName: meta.productName,
      spec: meta.spec,
      unit: meta.unit,
      bomVersion: 'V1',
      routeVersion: 'V1',
      planQuantity: item.plan,
      completedQuantity: item.plan,
      qualifiedQuantity: qualified,
      defectQuantity: item.defect,
      progress: 100,
      status: 6,
      statusDesc: '已完工',
      planStartTime: dayAt(item.offset, 8, 0),
      planEndTime: dayAt(item.offset, 16, 0),
      actualStartTime: dayAt(item.offset, 8, 6),
      actualEndTime: dayAt(item.offset, 15, 20),
      archived: true,
      remark: '历史完工订单（演示数据，用于展示近 7 天趋势）'
    });

    tasksByOrder[orderId] = [
      {
        id: 500 + index * 10 + 1, orderId, orderNo, stepNo: 10, stepName: '外壳组装',
        workCenter: '装配一线', standardHours: 0.25,
        planQuantity: item.plan, reportedQuantity: item.plan,
        qualifiedQuantity: qualified, defectQuantity: item.defect, actualHours: 2,
        status: 4, statusDesc: '已完成', isInspection: 0,
        operatorId: 4, operatorName: '王工',
        startTime: dayAt(item.offset, 8, 6), endTime: dayAt(item.offset, 11, 20)
      },
      {
        id: 500 + index * 10 + 2, orderId, orderNo, stepNo: 20, stepName: '老化测试',
        workCenter: '老化房', standardHours: 2,
        planQuantity: item.plan, reportedQuantity: item.plan,
        qualifiedQuantity: qualified, defectQuantity: item.defect, actualHours: 3,
        status: 4, statusDesc: '已完成', isInspection: 1,
        operatorId: 5, operatorName: '赵质检',
        startTime: dayAt(item.offset, 11, 30), endTime: dayAt(item.offset, 15, 20)
      }
    ];

    statusEvents[orderId] = [
      {
        fromStatus: 4, fromStatusDesc: '生产中', toStatus: 6, toStatusDesc: '已完工',
        reason: '全部工序完成，质量放行', operatorName: '系统',
        operateTime: dayAt(item.offset, 15, 20)
      },
      {
        fromStatus: 2, fromStatusDesc: '已审核', toStatus: 3, toStatusDesc: '已下达',
        reason: '计划确认', operatorName: '张计划',
        operateTime: dayAt(item.offset, 8, 0)
      }
    ];
  });

  window.MES_DEMO = {
    dateKey,
    orders,
    tasksByOrder,
    inventory,
    trace,
    qualityQueue,
    statusEvents,
    orderStatus,
    taskStatus,
    user: { id: 2, username: 'planner', realName: '张计划', roles: ['PLANNER'] },
    products: [
      { id: 1, productCode: 'PRD-DB-X1', productName: '智能电表 X1', spec: '220V/5(60)A', unit: '台' },
      { id: 2, productCode: 'PRD-DB-X2', productName: '智能电表 X2', spec: '220V/10(100)A', unit: '台' },
      { id: 3, productCode: 'PRD-SEMI-C1', productName: '电表控制板 C1', spec: '含 MCU + 计量芯片', unit: '块' }
    ]
  };
})();
