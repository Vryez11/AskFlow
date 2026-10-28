package io.askflow.pipeline.datasource

sealed interface ExecutionOutcome {
    data class Success(
        val result: QueryResult,
    ) : ExecutionOutcome

    data class Failure(
        val error: ExecutionError,
    ) : ExecutionOutcome
}
