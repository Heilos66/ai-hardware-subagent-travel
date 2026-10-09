# Koo Search 接口核实

接口核实日期：2026-09-28。当前默认 `KOOSEARCH_MODE=stub`；知识库确定并配置好后，切换 `live` 才会调用以下接口。真实 Koo Search 尚未联调。

采用当前 `SearchInKnowledgeRepo`：`POST /v1/{project_id}/applications/{application_id}/uni-search/experience/searchtext`。正文发送 `repo_id`、`content`、`page_num=1`、`page_size=3`、`scope=doc`；从返回的 `doc_list` 读取分片正文、标题和副标题。查询最长 1000 字符。可选 `search_threshold` 范围为 0～200，因此不直接沿用低代码的 0.3/0.1。[检索 API](https://support.huaweicloud.com/api-koosearch/SearchInKnowledgeRepo.html)

认证支持项目级 IAM Token（`X-Auth-Token`）和 APP 简易认证（`X-Apig-AppCode`）。本项目两种均支持，优先 AppCode。Token 模式由部署环境在过期前更新，支持按请求读取挂载文件，不内置用户密码登录。[Koo Search 鉴权](https://support.huaweicloud.com/api-koosearch/koosearch_06_0009.html)

服务地址与实例绑定：公网调用使用已发布的 APIG 地址和请求路径，内网地址从服务详情获取。因此 `KOOSEARCH_URL` 可覆盖拼接路径，用于实例公布的完整 URL；项目、应用、两个知识库标识均由环境配置，不猜测实际值。[接口地址获取](https://support.huaweicloud.com/api-koosearch/koosearch_08_0006.html)

旧路径 `/v1/koosearch/experience/search` 属历史 API，本实现默认使用当前接口，不通过失败重试自动切换版本。[历史搜索接口](https://support.huaweicloud.com/api-koosearch/SearchText.html)

工程约定：纯文本与图片分类检索分别配置知识库 ID；若同库可填相同值。只查询已有知识库，不创建、上传或索引文档。测试中的分片与账号数据均为自造示例。真实端点、权限和已索引内容仍需部署环境联调验证。
