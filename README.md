# AskFlow

## 분석 대상 DB 설정 (F-003)

api는 `askflow.target-db` 설정으로 분석 대상 DB에 읽기 전용으로 붙는다. 기본값은 `api/src/main/resources/application.yml`에 있고, 로컬 MySQL(`docker compose up -d`)의 `olist` 스키마와 `askflow_reader` 계정을 쓴다.

| 키 | 기본값 | 뜻 |
|---|---|---|
| `url` | `jdbc:mysql://localhost:${MYSQL_PORT:3306}/olist` | 접속 주소 |
| `schema` | `olist` | 분석할 스키마 |
| `username` | `askflow_reader` | 읽기 전용 계정 |
| `password` | `${ASKFLOW_READER_PASSWORD:}` | 늘 환경 변수로 받는다 |
| `allowed-tables` | `[]` | 조회를 허용할 테이블. 비우면 스키마의 모든 테이블·뷰 |
| `pii-columns` | `[]` | 개인정보 열(`table.column`). LLM에 값을 넘기지 않는다 |
| `max-rows` | `1000` | 결과 행 수 상한 |
| `query-timeout` | `10s` | 실행 시간 상한 |

LLM에는 안전이 확인된 열만 보낸다. 뷰에서 온 열, 생성 컬럼, 내장 함수 허용 목록 밖의 함수를 쓴 열은 `pii-columns`와 상관없이 빠진다. 다만 기본값이 `DEFAULT (식)`인 컬럼은 DB가 일반 컬럼으로 보여 주므로 걸러지지 않는다. 개인정보 컬럼을 복사해 두는 기본값 컬럼이 있으면 그 컬럼도 `pii-columns`에 적는다.

`./gradlew :api:bootRun`은 `.env`를 읽지 않는다. 셸에 `ASKFLOW_READER_PASSWORD`를 넣고 띄운다. 비밀번호가 없거나 틀려도 앱은 뜨고, `GET /api/connection/status`와 홈의 연결 상태가 원인과 확인할 설정을 보여 준다.

### 파일럿 설정 덮어쓰기

회사별 설정은 별도 파일에 바뀌는 키만 적고 `SPRING_CONFIG_ADDITIONAL_LOCATION`으로 덧붙인다. 비밀번호는 파일에 쓰지 않는다.

```yaml
# /etc/askflow/pilot.yml
askflow:
  target-db:
    url: jdbc:mysql://pilot-db.internal:3306/sales
    schema: sales
    username: askflow_reader
    allowed-tables: [orders, order_items, products, customers]
    pii-columns: [customers.email, customers.phone]
```

```sh
SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/askflow/pilot.yml \
ASKFLOW_READER_PASSWORD=... \
java -jar api.jar
```
