package com.ironhack.backend.overcast.web;

import com.ironhack.backend.overcast.ai.ExplanationService;
import com.ironhack.backend.overcast.service.ScanService;
import com.ironhack.backend.overcast.web.dto.Dtos.AskRequest;
import com.ironhack.backend.overcast.web.dto.Dtos.AskResponse;
import com.ironhack.backend.overcast.web.dto.Dtos.FindingsPage;
import com.ironhack.backend.overcast.web.dto.Dtos.OptimizedBill;
import com.ironhack.backend.overcast.web.dto.Dtos.ScanCreated;
import com.ironhack.backend.overcast.web.dto.Dtos.ScanSummary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/scans")
public class ScanController {

    private final ScanService scanService;
    private final ExplanationService explanationService;

    public ScanController(ScanService scanService, ExplanationService explanationService) {
        this.scanService = scanService;
        this.explanationService = explanationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScanCreated upload(@RequestParam("file") MultipartFile file,
                              @RequestParam(value = "provider", required = false) String provider)
            throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file upload");
        }
        String filename = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            ScanSummary summary = scanService.ingest(reader, filename, null, provider);
            return new ScanCreated(summary.scanId(), summary);
        }
    }

    @GetMapping("/{id}/summary")
    public ScanSummary summary(@PathVariable String id) {
        return scanService.summary(id);
    }

    /** The load-test target: served from Redis when warm. */
    @GetMapping("/{id}/findings")
    public FindingsPage findings(@PathVariable String id,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        int boundedSize = Math.clamp(size, 1, 200);
        return scanService.findings(id, Math.max(page, 0), boundedSize);
    }

    @GetMapping("/{id}/optimized")
    public OptimizedBill optimized(@PathVariable String id) {
        return scanService.optimized(id);
    }

    @PostMapping("/{id}/ask")
    public AskResponse ask(@PathVariable String id, @RequestBody AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        var answer = explanationService.ask(
                request.question(),
                scanService.contextJson(id, 20),
                scanService.topFindings(id, 5));
        return new AskResponse(answer.explanation(), answer.source());
    }
}
