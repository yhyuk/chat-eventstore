package com.example.chat.projection.controller;

import com.example.chat.projection.dto.RebuildResult;
import com.example.chat.projection.service.ProjectionRebuildService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Admin-only projection operations. See DlqAdminController for the same non-goal auth note.
@RestController
@RequestMapping("/admin/projections")
@RequiredArgsConstructor
public class AdminProjectionController {

    private final ProjectionRebuildService rebuildService;

    @PostMapping("/rebuild")
    public RebuildResult rebuild(@RequestParam Long sessionId) {
        return rebuildService.rebuild(sessionId);
    }
}
