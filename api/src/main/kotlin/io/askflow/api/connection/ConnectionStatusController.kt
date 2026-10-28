package io.askflow.api.connection

import io.askflow.pipeline.datasource.ConnectionFailureReason
import io.askflow.pipeline.datasource.ConnectionState
import io.askflow.pipeline.datasource.DataSourceConnector
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 연결 상태 조회 `GET /api/connection/status` (data-connection "연결 상태 조회", design 8장).
 * 조회할 때마다 연결을 다시 확인한다.
 */
@RestController
@RequestMapping("/api/connection")
class ConnectionStatusController(
    private val connector: DataSourceConnector,
    private val termCountProvider: TermCountProvider,
) {
    @GetMapping("/status")
    fun status(): ConnectionStatusResponse = TODO()
}

/** 비밀번호는 담지 않는다 */
data class ConnectionStatusResponse(
    val state: ConnectionState,
    val tableCount: Int,
    val termCount: Int,
    val writePrivileges: List<String>,
    val warnings: List<String>,
    val failure: FailureResponse?,
    val checkedAt: Instant,
) {
    data class FailureResponse(
        val reason: ConnectionFailureReason,
        val message: String,
        val checkKeys: List<String>,
    )
}
