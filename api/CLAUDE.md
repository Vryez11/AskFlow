# api

- Spring Boot 4.1이다. 테스트 애너테이션 패키지가 3.x와 다르다: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `...AutoConfigureMockMvc`. JSON은 Jackson 3(`tools.jackson.*`)이고 `Instant`는 ISO 문자열로 나간다.
- 분석 대상 DB 연결 코드는 `io.askflow.api.connection`에 둔다. 커넥터 구현은 pipeline에 있고, api는 설정을 바인딩해 빈으로 등록만 한다.
- `@ConfigurationProperties` 검증이 실패하면 `ApplicationContextRunner`의 `startupFailure` 원인 사슬 메시지에 kebab 형식 키(예: `askflow.target-db.max-rows`)가 들어 있다. `Duration`에는 `@Positive`가 안 되니 `@DurationMin`을 쓴다.
- 앱 전체를 띄우는 테스트는 분석 대상 DB 없이 뜬다. `MYSQL_PORT=1`로 꺼진 DB를 흉내 낸다.
