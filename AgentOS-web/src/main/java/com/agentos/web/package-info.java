/**
 * 核心能力五：Web Service。
 *
 * <p>六个 {@code ApiController} 提供核心阶段 10 个端点，{@code GlobalExceptionHandler} 统一返回
 * {@code ApiResponse}，springdoc 生成 OpenAPI 文档。同步等待超时返回 202，会话冲突返回 409，见技术方案第 7 章。
 *
 * <p>当前目录还没有这些 ApiController；目前已经存在的是统一响应、异常处理和 CORS 配置。
 */
package com.agentos.web;
