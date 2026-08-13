(function() {
  var style = getComputedStyle(document.documentElement);
  var accent = style.getPropertyValue('--accent').trim();
  var accent2 = style.getPropertyValue('--accent2').trim();
  var ink = style.getPropertyValue('--ink').trim();
  var muted = style.getPropertyValue('--muted').trim();
  var rule = style.getPropertyValue('--rule').trim();
  var bg2 = style.getPropertyValue('--bg2').trim();
  var danger = style.getPropertyValue('--danger').trim();
  var warning = style.getPropertyValue('--warning').trim();
  var success = style.getPropertyValue('--success').trim();

  // --- Chart 1: Effort by Phase ---
  var chart1 = echarts.init(document.getElementById('chart-effort'), null, { renderer: 'svg' });
  chart1.setOption({
    animation: false,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      appendToBody: true,
      backgroundColor: bg2,
      borderColor: rule,
      textStyle: { color: ink }
    },
    legend: {
      data: ['P0 阻塞', 'P1 严重', 'P2 建议'],
      textStyle: { color: muted },
      top: 0
    },
    grid: { left: '8%', right: '8%', bottom: '10%', top: '15%' },
    xAxis: {
      type: 'category',
      data: ['Phase 1\nP0 修复', 'Phase 2\nP1 修复', 'Phase 3\n验证', 'Phase 4\n发布'],
      axisLine: { lineStyle: { color: rule } },
      axisLabel: { color: muted, fontSize: 11, lineHeight: 14 }
    },
    yAxis: {
      type: 'value',
      name: '工时 (天)',
      nameTextStyle: { color: muted },
      axisLine: { lineStyle: { color: rule } },
      axisLabel: { color: muted },
      splitLine: { lineStyle: { color: rule, type: 'dashed' } }
    },
    series: [
      {
        name: 'P0 阻塞',
        type: 'bar',
        data: [11, 0, 0, 0],
        itemStyle: { color: danger, borderRadius: [4, 4, 0, 0] },
        barWidth: '20%'
      },
      {
        name: 'P1 严重',
        type: 'bar',
        data: [0, 4.5, 8, 0],
        itemStyle: { color: warning, borderRadius: [4, 4, 0, 0] },
        barWidth: '20%'
      },
      {
        name: 'P2 建议',
        type: 'bar',
        data: [0, 0, 0, 10],
        itemStyle: { color: success, borderRadius: [4, 4, 0, 0] },
        barWidth: '20%'
      }
    ]
  });
  window.addEventListener('resize', function() { chart1.resize(); });

  // --- Chart 2: Priority Matrix (Impact vs Effort) ---
  var chart2 = echarts.init(document.getElementById('chart-matrix'), null, { renderer: 'svg' });
  chart2.setOption({
    animation: false,
    tooltip: {
      trigger: 'item',
      appendToBody: true,
      backgroundColor: bg2,
      borderColor: rule,
      textStyle: { color: ink },
      formatter: function(p) {
        return '<b>' + p.data[3] + '</b><br/>工时: ' + p.data[0] + ' 天<br/>影响度: ' + p.data[1] + '/10';
      }
    },
    grid: { left: '10%', right: '10%', bottom: '12%', top: '8%' },
    xAxis: {
      type: 'value',
      name: '工时 (天)',
      nameLocation: 'middle',
      nameGap: 30,
      nameTextStyle: { color: muted },
      min: 0,
      max: 8,
      axisLine: { lineStyle: { color: rule } },
      axisLabel: { color: muted },
      splitLine: { lineStyle: { color: rule, type: 'dashed' } }
    },
    yAxis: {
      type: 'value',
      name: '影响度 (1-10)',
      nameLocation: 'middle',
      nameGap: 40,
      nameTextStyle: { color: muted },
      min: 0,
      max: 11,
      axisLine: { lineStyle: { color: rule } },
      axisLabel: { color: muted },
      splitLine: { lineStyle: { color: rule, type: 'dashed' } }
    },
    series: [
      {
        type: 'scatter',
        symbolSize: function(data) { return Math.max(data[1] * 4, 16); },
        data: [
          // [effort, impact, priority, label]
          [6.5, 10, 'P0', 'P0-1 标定数据'],
          [3.5, 9, 'P0', 'P0-2 误报精修'],
          [1.0, 8, 'P0', 'P0-3 阈值统一'],
          [3.0, 7, 'P1', 'P1-1 24h压测'],
          [5.0, 7, 'P1', 'P1-2 安全审计'],
          [2.0, 6, 'P1', 'P1-3 签名验证'],
          [1.5, 5, 'P1', 'P1-4 Shizuku拆分'],
          [0.5, 5, 'P1', 'P1-5 Release签名'],
          [0.5, 4, 'P1', 'P1-6 state.rs补全'],
          [3.0, 4, 'P2', 'P2-1 iforest权重'],
          [2.0, 3, 'P2', 'P2-2 CtxProbe补全'],
          [0.5, 2, 'P2', 'P2-3 period_energy'],
          [0.5, 2, 'P2', 'P2-4 死代码清理'],
          [0.5, 2, 'P2', 'P2-5 压测门控'],
          [1.0, 3, 'P2', 'P2-6 持久化映射'],
          [2.0, 3, 'P2', 'P2-7 crypto迁移'],
          [0.5, 1, 'P2', 'P2-8 VecDeque优化']
        ],
        itemStyle: {
          color: function(params) {
            var p = params.data[2];
            if (p === 'P0') return danger;
            if (p === 'P1') return warning;
            return success;
          },
          opacity: 0.8
        },
        label: {
          show: true,
          formatter: function(p) { return p.data[3].split(' ')[0]; },
          position: 'top',
          color: ink,
          fontSize: 10
        },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: rule, type: 'dashed' },
          data: [
            { xAxis: 2.5 },
            { yAxis: 6.5 }
          ]
        },
        markArea: {
          silent: true,
          itemStyle: { color: 'rgba(52,211,153,0.04)' },
          data: [[
            { coord: [0, 6.5] },
            { coord: [2.5, 11] }
          ]]
        }
      }
    ],
    graphic: [
      {
        type: 'text',
        right: '12%',
        top: '10%',
        style: { text: '高影响 / 低工时\n(立即做)', fill: success, fontSize: 11, textAlign: 'center' }
      },
      {
        type: 'text',
        right: '12%',
        bottom: '15%',
        style: { text: '高影响 / 高工时\n(重点投入)', fill: warning, fontSize: 11, textAlign: 'center' }
      },
      {
        type: 'text',
        left: '12%',
        top: '10%',
        style: { text: '低影响 / 低工时\n(随手做)', fill: accent2, fontSize: 11, textAlign: 'center' }
      },
      {
        type: 'text',
        left: '12%',
        bottom: '15%',
        style: { text: '低影响 / 高工时\n(暂缓)', fill: muted, fontSize: 11, textAlign: 'center' }
      }
    ]
  });
  window.addEventListener('resize', function() { chart2.resize(); });
})();
