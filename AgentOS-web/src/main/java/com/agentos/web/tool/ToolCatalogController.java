package com.agentos.web.tool;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.tool.ToolCatalogQuery;
import com.agentos.core.tool.ToolCatalogView;
import com.agentos.web.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 只投影目录；所有异常固定安全响应，不附加载器的文件路径或SDK异常。 */
@org.springframework.web.bind.annotation.RestController
@org.springframework.context.annotation.Conditional(ToolQueryEnabledCondition.class)
public final class ToolCatalogController {
    private final ToolCatalogQuery query;

    public ToolCatalogController(ToolCatalogQuery query) {
        this.query = query;
    }

    @GetMapping("/api/v1/tools")
    public ResponseEntity<ApiResponse<ToolCatalogView>> list(
            @RequestParam(required = false) String agent) {
        try {
            if (agent != null && (agent.isBlank() || agent.length() > 128)) {
                throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "参数不合法");
            }
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(ApiResponse.ok(query.snapshot(agent)));
        } catch (AgentOSException invalid) {
            boolean missing = invalid.getErrorCode() == ErrorCode.NOT_FOUND;
            return ResponseEntity.status(missing ? 404 : 400)
                    .body(
                            ApiResponse.error(
                                    missing ? ErrorCode.NOT_FOUND : ErrorCode.INVALID_ARGUMENT,
                                    missing ? "Agent不存在" : "参数或Agent配置不合法"));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorCode.INVALID_ARGUMENT, "参数不合法"));
        } catch (RuntimeException failed) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, "内部错误"));
        }
    }
}
