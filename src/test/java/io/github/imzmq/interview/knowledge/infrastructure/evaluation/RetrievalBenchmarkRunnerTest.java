package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalBenchmarkRunnerTest {

    @Test
    void dispatchesExactlyTheConfiguredOperation() {
        RetrievalBenchmarkProperties properties = new RetrievalBenchmarkProperties();
        properties.setOperation(BenchmarkOperation.CLEAN);
        RetrievalBenchmarkFileService service = mock(RetrievalBenchmarkFileService.class);
        when(service.execute(BenchmarkOperation.CLEAN)).thenReturn(
                new RetrievalBenchmarkFileResult(
                        BenchmarkOperation.CLEAN, 0, 0, null, null, null));
        RetrievalBenchmarkRunner runner = new RetrievalBenchmarkRunner(properties, service);

        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(service).execute(BenchmarkOperation.CLEAN);
    }
}
