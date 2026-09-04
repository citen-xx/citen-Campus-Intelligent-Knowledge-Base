package com.ruoyi.web.controller.system;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.service.PublicKnowledgeCacheService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system/ai/metrics")
public class AiMetricsController
{
    private final PublicKnowledgeCacheService publicKnowledgeCacheService;

    public AiMetricsController(PublicKnowledgeCacheService publicKnowledgeCacheService)
    {
        this.publicKnowledgeCacheService = publicKnowledgeCacheService;
    }

    @PreAuthorize("@ss.hasPermi('system:knowledge:list')")
    @GetMapping("/cache")
    public AjaxResult cacheMetrics()
    {
        return AjaxResult.success(publicKnowledgeCacheService.metrics());
    }
}
