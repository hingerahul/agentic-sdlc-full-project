package com.hingera.agenticsdlc.run;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @GetMapping
    public List<EngineeringRun> findAll() {
        return runService.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EngineeringRun create(@Valid @RequestBody CreateRunRequest request) {
        return runService.create(request.requirement());
    }

    @GetMapping("/{id}")
    public EngineeringRun findById(@PathVariable UUID id) {
        return runService.findById(id);
    }

    @GetMapping("/{id}/orchestration")
    public OrchestrationSnapshot orchestrationSnapshot(@PathVariable UUID id) {
        return runService.orchestrationSnapshot(id);
    }

    @PostMapping("/{id}/execute")
    public EngineeringRun execute(@PathVariable UUID id) {
        return runService.execute(id);
    }

    @PostMapping("/{id}/review")
    public EngineeringRun review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        return runService.review(id, request);
    }
}
