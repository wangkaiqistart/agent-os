# US8 Brave搜索验证

2026-11-19 01:38:34 +08:00，阶段定向verify PASS，32.406s，10 reactor质量检查成功，独立PostgreSQL，无真实搜索请求。

单元/协议测试先3项行为红灯，再实现输入/配置、GET q/count、X-Subscription-Token及web.results映射。使用本地HTTP服务验证有结果、完整空、未配置、拒绝、失败、超限；非法JSON/不安全链接/307/500响应与超字节上限均有明确结果。结果≤10，超条数或字符标记truncated，不抓取结果页面，下一跳/命中页请求0。超大响应不解析部分JSON为成功。

应用级先UnknownAgentToolException行为红灯，再注册；有配置与未配置各用真实Manager/PostgreSQL闭环，检查原callId/source/attempts及下一次模型回填。未配置可在Agent中声明但模型tools数0，伪造调用UNCONFIGURED、请求0、attempts0。未授权/非法输入/begin失败/取消/租约失效请求0；complete未确认只有一次真实请求。凭证只在服务头，模型与持久化审计秘密哨兵0。

重放：

```bash
mvn -B -ntp -pl AgentOS-boot -am -Dtest=WebSearchContractTest,BraveSearchProviderContractTest,WebSearchAcceptanceIntegrationTest,WebSearchUnconfiguredAcceptanceIntegrationTest,ToolAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

配置agentos.tools.search：provider=brave、endpoint默认https://api.search.brave.com/res/v1/web/search、api-key使用AGENTOS_SEARCH_API_KEY环境引用，query≤1000、results≤10、response≤1MiB、timeout默认30s；HTTP allowed-domains必须显式授权。缺凭证只使本工具UNCONFIGURED，不阻塞其他工具。凭证加入Redactor秘密集，配置toString不打印原值。

公开文档站连接20s超时；协议按规划字段与本地真实HTTP请求核对。真实Brave兼容性NOT_RUN，不以替身通过声称外部服务可用。持续正文20次样本纳入最终跨故事时限验收。安全日志索引：/tmp/agentos-us8-verify.log。
