# COMP-ENE-04：已知报告问题定向收口

日期：2026-11-02。独立 DevTools 自动化端口 9441；前端 `mp-agent-business`；Java 3215，最终进程 `business-preview-java-3215-business-v11`。沿用开发者工具当前真实登录账号、Company 来源，没有注入身份、会话、卡片或报告。后台所在办公室页面不改变公司报告的来源范围。

## 操作与观察

1. 从真实 Company 输入框发送「请生成最近一周公司的能耗报告附件，提供预览和PDF、Excel下载。不要创建任务。」（`01-v9-report-request.png`）。修复前同一工具返回未完成，正文称服务未接入。
2. 修复报告模块注册后，看到真实报告附件（`02-v9-report-result.png`），点击后看到已保存报告预览（`04-v9-report-preview-loaded.png`）：43 台有效样本、902.85 度，实际采样为 10-25 20:00 至 11-01 20:00；请求期间仍为 10-26 00:00 至 11-02 00:00。预览明确保留客户模拟补数提示，不作为真实运行或节能证据。
3. 点击原生「下载 PDF」按钮后，通过小程序只读文件系统读取其实际下载的文件，不另发下载请求。旧文件 `report-downloaded-v9.pdf` 用独立 PDF 阅读器渲染后中文乱码，失败截图 `08-v9-downloaded-pdf-page1.png` 保留。未把文件头或 HTTP 成功算作可用。
4. 更换内嵌字体的 PDF 渲染器后，重启隔离 Java、从同一附件重开同一 reportId，再正常点击下载。v10 中文与数值已可读（`12-v10-downloaded-pdf-page1.png`）。随后补齐 PDF/XLSX 中的实际采样覆盖、逐表起止累计示数和模拟补数披露，不能让导出遗漏预览中的关键数据口径。
5. 最终 v11 重启后，同一真实附件仍可打开，见 `14-v11-report-preview-before-pdf.png`；没有重新生成另一份报告。分别正常点击「导出表格」「下载 PDF」，实际下载文件经小程序只读文件系统取出落盘。PDF 用独立阅读器实际解析并渲染（`15-v11-downloaded-pdf-page1.png`）；XLSX 用标准 openpyxl 阅读，43 行用量均为数值单元格，求和为 902.85，逐表采样时刻和累计示数存在且示数为数值。导出文件均保留客户模拟补数说明。

## 最终产物

|文件|观察|SHA256|
|---|---|---|
|`report-downloaded-v11.pdf`|3 页；中文、报告标识、902.85、实际采样范围、逐表示数、模拟补数提示可读|`09a55318d27b4db1cf68cbfccd04d38c01e3daaafea540e8236f8005dde32e6b`|
|`report-downloaded-v11.xlsx`|Report / Energy / Safety / Inspection；43 台表，数值用量合计 902.85，采样时间与累计示数保留|`ed0f3bbc5c3e7505dfab6ee6e615f8d324c398a5ed2469648443c5be9f558d20`|

Java 预览包：`var/business-preview/iot-agent-business-fixes-preview-v11-20261102.jar`，SHA256 `0fd0d0ac3f67b2d308f4f5f43280d3b0c767f77b1ad983e803cb3f57d20e8d7c`。这是已运行完整 v7 包叠加定向验证业务类/资源/必要依赖的隔离预览；不是整个最新工作区源码的完整构建，未吸收另一线程的 Pi Session MySQL 迁移。启动与来源见 [runbook](../../local-preview.md#9-当前已知业务修复预览2026-11-02-1349)。

## 工程验证与边界

- `ReportApplicationLifecycleTest` 以生产组件扫描先于存储配置的顺序复现缺少 `ReportGenerationPort`；修复后真实模块生成/读取/下载通过。
- `ReportExportRendererTest` 使用标准 PDF 解析器检查中文事实及字体嵌入，不再直接解码手写内容流来冒充阅读器验证；新增采样覆盖、累计示数和模拟补数保留回归。字体嵌入和采样保留两个反例均记录过修复前失败、修复后通过。
- 与能耗、报告、台账、草案恢复、巡检相关 9 组定向回归共 66 项通过；不是全仓构建或全部业务通过。
- 全程没有控制设备、创建生产任务、发送通知，也没有修改/测试生产 Runtime。没有篡改上游模拟补数，报告按既有 Runtime 规则披露其性质。
- 本次仅证明报告附件生成、预览、跨 Java 重启读取及实际下载文件可解析/渲染。真机微信原生阅读器、引用追问、所有其他卡片与其他报告期间未验收；不扩展为全部票据关闭。
