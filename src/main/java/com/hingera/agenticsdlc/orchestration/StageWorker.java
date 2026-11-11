package com.hingera.agenticsdlc.orchestration;

import com.hingera.agenticsdlc.run.EngineeringRun;

import java.util.Map;

@FunctionalInterface
public interface StageWorker {
    String execute(StageDefinition stage, EngineeringRun run, Map<String, String> priorOutputs) throws Exception;
}
